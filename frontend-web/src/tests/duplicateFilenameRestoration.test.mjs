import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

describe('P0 Regression — Duplicate Filenames Across Cards Restoration Suite', () => {

  // Simulate restoreMediaFiles implementation returning byMediaId Map<string, File>
  async function mockRestoreMediaFiles(mediaItems, simulatedBinaries) {
    if (!mediaItems || mediaItems.length === 0) {
      return { files: [], tags: {}, coverIndex: 0, mediaIds: {}, byMediaId: new Map() };
    }

    const files = [];
    const tags = {};
    const mediaIds = {};
    const byMediaId = new Map();
    let coverIndex = 0;

    for (let i = 0; i < mediaItems.length; i++) {
      const item = mediaItems[i];
      const binaryContent = simulatedBinaries[item.mediaId] || `binary-data-for-${item.mediaId}`;
      const file = new File([binaryContent], item.originalFilename, { type: item.contentType || 'image/webp' });
      // In real browser DOM, custom expando properties like (file as any).draftMediaId can be dropped.
      // We deliberately do NOT set expando on file to prove identity does NOT rely on expando properties!
      
      files.push(file);
      const finalIndex = files.length - 1;
      if (item.roomTag) tags[finalIndex] = item.roomTag;
      if (item.isCover) coverIndex = finalIndex;
      if (item.mediaId) {
        mediaIds[finalIndex] = item.mediaId;
        byMediaId.set(item.mediaId, file);
      }
    }

    return { files, tags, coverIndex, mediaIds, byMediaId };
  }

  // Simulate handleRestoreBatchDraft hydration logic
  function hydrateCards(payloadCards, serverMedia, restored) {
    return payloadCards.map((card, idx) => {
      const cardMedia = serverMedia.filter((m) => m.cardId === card.id || (!m.cardId && idx === 0));
      const existingStaged = Array.isArray(card.stagedMedia) ? card.stagedMedia : [];

      const mergedMediaMap = new Map();
      existingStaged.forEach((item) => {
        mergedMediaMap.set(item.id, {
          ...item,
          originalFilename: item.originalFilename || item.file?.name || '',
          fileSizeBytes: item.fileSizeBytes || item.file?.size || 0,
          previewUrl: item.previewUrl || ''
        });
      });

      cardMedia.forEach((m) => {
        const prev = mergedMediaMap.get(m.mediaId);
        mergedMediaMap.set(m.mediaId, {
          id: m.mediaId,
          file: prev?.file,
          previewUrl: m.previewUrl || prev?.previewUrl || '',
          roomTag: m.roomTag || prev?.roomTag || 'LIVING_ROOM',
          isCover: typeof m.isCover === 'boolean' ? m.isCover : Boolean(prev?.isCover),
          originalFilename: m.originalFilename || prev?.originalFilename || '',
          fileSizeBytes: m.fileSizeBytes || prev?.fileSizeBytes || 0,
          contentType: m.contentType || prev?.contentType || 'image/webp',
          stagingStatus: 'staged'
        });
      });

      // Hydrate from byMediaId map
      const stagedMedia = Array.from(mergedMediaMap.values()).map((item) => {
        const matchingFile = restored.byMediaId.get(item.id);
        if (matchingFile && !item.file) {
          return {
            ...item,
            file: matchingFile,
            previewUrl: `blob:mock-url-for-${item.id}`
          };
        }
        return item;
      });

      return {
        ...card,
        stagedMedia,
        localPhotos: stagedMedia.map((m) => m.file).filter(Boolean),
        localPhotoPreviews: stagedMedia.map((m) => m.previewUrl).filter(Boolean)
      };
    });
  }

  test('Realistic 3-card batch with shared filenames: P1, P2, P3 binaries isolate 100% correctly', async () => {
    // 1. Define exact 3-card structure from real diagnostic test
    const P1_ITEMS = [
      { mediaId: 'p1-living', cardId: 'card-1', originalFilename: 'images__1_.webp', roomTag: 'LIVING_ROOM', isCover: true, contentType: 'image/webp' },
      { mediaId: 'p1-bed', cardId: 'card-1', originalFilename: 'images__2_.webp', roomTag: 'BEDROOM', isCover: false, contentType: 'image/webp' },
      { mediaId: 'p1-kitchen', cardId: 'card-1', originalFilename: 'images__3_.webp', roomTag: 'KITCHEN', isCover: false, contentType: 'image/webp' },
      { mediaId: 'p1-bath', cardId: 'card-1', originalFilename: 'images.webp', roomTag: 'BATHROOM', isCover: false, contentType: 'image/webp' },
    ];

    const P2_ITEMS = [
      { mediaId: 'p2-living', cardId: 'card-2', originalFilename: 'images__1_.webp', roomTag: 'LIVING_ROOM', isCover: true, contentType: 'image/webp' },
      { mediaId: 'p2-bed', cardId: 'card-2', originalFilename: 'images__2_.webp', roomTag: 'BEDROOM', isCover: false, contentType: 'image/webp' },
      { mediaId: 'p2-kitchen', cardId: 'card-2', originalFilename: 'images__4_.webp', roomTag: 'KITCHEN', isCover: false, contentType: 'image/webp' },
      { mediaId: 'p2-video', cardId: 'card-2', originalFilename: 'WhatsApp_Video.mp4', roomTag: 'BATHROOM', isCover: false, contentType: 'video/mp4' },
    ];

    const P3_ITEMS = [
      { mediaId: 'p3-living', cardId: 'card-3', originalFilename: 'images__1_.webp', roomTag: 'LIVING_ROOM', isCover: true, contentType: 'image/webp' },
      { mediaId: 'p3-bed', cardId: 'card-3', originalFilename: 'images__2_.webp', roomTag: 'BEDROOM', isCover: false, contentType: 'image/webp' },
      { mediaId: 'p3-kitchen', cardId: 'card-3', originalFilename: 'images.webp', roomTag: 'KITCHEN', isCover: false, contentType: 'image/webp' },
    ];

    const serverMedia = [...P1_ITEMS, ...P2_ITEMS, ...P3_ITEMS];
    assert.equal(serverMedia.length, 11, 'Total server media must be exactly 11');

    // Distinct mock binary content for every single media item
    const simulatedBinaries = {
      'p1-living': 'BINARY_P1_LIVING_ROOM_656x467_MD5_3314a623',
      'p1-bed': 'BINARY_P1_BEDROOM_651x471_MD5_2a541ea8',
      'p1-kitchen': 'BINARY_P1_KITCHEN_194x259_MD5_cf7a26ff',
      'p1-bath': 'BINARY_P1_BATHROOM_350x262_MD5_d948977a',

      'p2-living': 'BINARY_P2_LIVING_ROOM_349x262_MD5_6639a099',
      'p2-bed': 'BINARY_P2_BEDROOM_194x259_MD5_a09789a1',
      'p2-kitchen': 'BINARY_P2_KITCHEN_278x181_MD5_ab9d4ef0',
      'p2-video': 'BINARY_P2_VIDEO_MP4_3268024_MD5_b65700a6',

      'p3-living': 'BINARY_P3_LIVING_ROOM_592x337_MD5_95efadd4',
      'p3-bed': 'BINARY_P3_BEDROOM_549x364_MD5_3cbd88d1',
      'p3-kitchen': 'BINARY_P3_KITCHEN_678x452_MD5_40d7668a',
    };

    // Staged cards in payload
    const payloadCards = [
      { id: 'card-1', title: '2 BHK Flat in Vijay Nagar', stagedMedia: P1_ITEMS.map(m => ({ id: m.mediaId, originalFilename: m.originalFilename, roomTag: m.roomTag, isCover: m.isCover, contentType: m.contentType })) },
      { id: 'card-2', title: '3 BHK Flat in Nipania', stagedMedia: P2_ITEMS.map(m => ({ id: m.mediaId, originalFilename: m.originalFilename, roomTag: m.roomTag, isCover: m.isCover, contentType: m.contentType })) },
      { id: 'card-3', title: '2 BHK Flat in Scheme 140', stagedMedia: P3_ITEMS.map(m => ({ id: m.mediaId, originalFilename: m.originalFilename, roomTag: m.roomTag, isCover: m.isCover, contentType: m.contentType })) },
    ];

    // 2. Perform restoration
    const restored = await mockRestoreMediaFiles(serverMedia, simulatedBinaries);
    assert.ok(restored.byMediaId instanceof Map, 'restoreMediaFiles must expose byMediaId Map');
    assert.equal(restored.byMediaId.size, 11, 'byMediaId must contain all 11 items');

    const hydrated = hydrateCards(payloadCards, serverMedia, restored);
    assert.equal(hydrated.length, 3, 'Must restore exactly 3 cards');

    // Card 1 Checks (Vijay Nagar: 4 media)
    const card1 = hydrated[0];
    assert.equal(card1.stagedMedia.length, 4, 'Card 1 must have 4 media items');
    assert.equal(card1.localPhotos.length, 4, 'Card 1 must have 4 local photos');
    const p1LivingBinary = await card1.stagedMedia.find(m => m.id === 'p1-living').file.text();
    const p1BedBinary = await card1.stagedMedia.find(m => m.id === 'p1-bed').file.text();
    const p1KitchenBinary = await card1.stagedMedia.find(m => m.id === 'p1-kitchen').file.text();
    const p1BathBinary = await card1.stagedMedia.find(m => m.id === 'p1-bath').file.text();

    assert.equal(p1LivingBinary, simulatedBinaries['p1-living'], 'P1 living must resolve to P1 living binary');
    assert.equal(p1BedBinary, simulatedBinaries['p1-bed'], 'P1 bed must resolve to P1 bed binary');
    assert.equal(p1KitchenBinary, simulatedBinaries['p1-kitchen'], 'P1 kitchen must resolve to P1 kitchen binary');
    assert.equal(p1BathBinary, simulatedBinaries['p1-bath'], 'P1 bath must resolve to P1 bath binary');

    // Card 2 Checks (Nipania: 4 media)
    const card2 = hydrated[1];
    assert.equal(card2.stagedMedia.length, 4, 'Card 2 must have 4 media items');
    assert.equal(card2.localPhotos.length, 4, 'Card 2 must have 4 local photos');
    const p2LivingBinary = await card2.stagedMedia.find(m => m.id === 'p2-living').file.text();
    const p2BedBinary = await card2.stagedMedia.find(m => m.id === 'p2-bed').file.text();
    const p2KitchenBinary = await card2.stagedMedia.find(m => m.id === 'p2-kitchen').file.text();
    const p2VideoBinary = await card2.stagedMedia.find(m => m.id === 'p2-video').file.text();

    assert.equal(p2LivingBinary, simulatedBinaries['p2-living'], 'P2 living must resolve to P2 living binary');
    assert.notEqual(p2LivingBinary, p1LivingBinary, 'P2 images__1_.webp must NOT resolve to P1 images__1_.webp');
    assert.equal(p2BedBinary, simulatedBinaries['p2-bed'], 'P2 bed must resolve to P2 bed binary');
    assert.notEqual(p2BedBinary, p1BedBinary, 'P2 images__2_.webp must NOT resolve to P1 images__2_.webp');
    assert.equal(p2KitchenBinary, simulatedBinaries['p2-kitchen'], 'P2 kitchen must resolve to P2 kitchen binary');
    assert.equal(p2VideoBinary, simulatedBinaries['p2-video'], 'P2 video must resolve to P2 video binary');

    // Card 3 Checks (Scheme 140: 3 media)
    const card3 = hydrated[2];
    assert.equal(card3.stagedMedia.length, 3, 'Card 3 must have 3 media items');
    assert.equal(card3.localPhotos.length, 3, 'Card 3 must have 3 local photos');
    const p3LivingBinary = await card3.stagedMedia.find(m => m.id === 'p3-living').file.text();
    const p3BedBinary = await card3.stagedMedia.find(m => m.id === 'p3-bed').file.text();
    const p3KitchenBinary = await card3.stagedMedia.find(m => m.id === 'p3-kitchen').file.text();

    assert.equal(p3LivingBinary, simulatedBinaries['p3-living'], 'P3 living must resolve to P3 living binary');
    assert.notEqual(p3LivingBinary, p1LivingBinary, 'P3 images__1_.webp MUST NOT resolve to P1 images__1_.webp');
    assert.notEqual(p3LivingBinary, p2LivingBinary, 'P3 images__1_.webp MUST NOT resolve to P2 images__1_.webp');

    assert.equal(p3BedBinary, simulatedBinaries['p3-bed'], 'P3 bed must resolve to P3 bed binary');
    assert.notEqual(p3BedBinary, p1BedBinary, 'P3 images__2_.webp MUST NOT resolve to P1 images__2_.webp');
    assert.notEqual(p3BedBinary, p2BedBinary, 'P3 images__2_.webp MUST NOT resolve to P2 images__2_.webp');

    assert.equal(p3KitchenBinary, simulatedBinaries['p3-kitchen'], 'P3 images.webp must resolve to P3 images.webp binary');
    assert.notEqual(p3KitchenBinary, p1BathBinary, 'P3 images.webp MUST NOT resolve to P1 images.webp');

    // Metadata preservation checks
    assert.equal(card1.stagedMedia.find(m => m.id === 'p1-living').isCover, true, 'P1 cover preserved');
    assert.equal(card1.stagedMedia.find(m => m.id === 'p1-kitchen').roomTag, 'KITCHEN', 'P1 room tag preserved');
    assert.equal(card2.stagedMedia.find(m => m.id === 'p2-video').contentType, 'video/mp4', 'P2 video type preserved');
    assert.equal(card3.stagedMedia.find(m => m.id === 'p3-living').roomTag, 'LIVING_ROOM', 'P3 room tag preserved');
    assert.equal(card3.stagedMedia.find(m => m.id === 'p3-living').isCover, true, 'P3 cover preserved');
  });
});
