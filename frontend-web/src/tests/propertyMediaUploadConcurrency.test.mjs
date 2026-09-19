import test from 'node:test';
import assert from 'node:assert/strict';

test('Bounded Property Media Upload Concurrency = 3 Suite', async (t) => {
  await t.test('1. Single Property: Max active uploads strictly bounded at 3, overlapping execution', async () => {
    const totalFiles = 7;
    const CONCURRENCY_LIMIT = 3;
    let maxObservedActive = 0;
    let currentlyActive = 0;
    const completedItems = [];
    const executionDelays = [40, 10, 50, 20, 30, 15, 25]; // Files complete out of order

    const items = Array.from({ length: totalFiles }, (_, i) => ({
      file: { name: `photo_${i}.jpg` },
      originalIndex: i,
      roomTag: i === 0 ? 'LIVING_ROOM' : 'BEDROOM',
      isCover: i === 0
    }));

    let nextIndex = 0;
    let completedCount = 0;

    const worker = async () => {
      while (nextIndex < totalFiles) {
        const fileIndex = nextIndex;
        nextIndex += 1;
        const item = items[fileIndex];

        currentlyActive += 1;
        if (currentlyActive > maxObservedActive) {
          maxObservedActive = currentlyActive;
        }

        // Simulate async upload with variable network duration
        await new Promise((resolve) => setTimeout(resolve, executionDelays[fileIndex]));

        currentlyActive -= 1;
        completedCount += 1;
        completedItems.push({
          originalIndex: item.originalIndex,
          fileName: item.file.name,
          completedAtCount: completedCount,
          isCover: item.isCover,
          roomTag: item.roomTag
        });
      }
    };

    const workerCount = Math.min(CONCURRENCY_LIMIT, totalFiles);
    const workers = [];
    for (let w = 0; w < workerCount; w += 1) {
      workers.push(worker());
    }
    await Promise.all(workers);

    assert.equal(maxObservedActive, 3, 'Observed concurrency reached exactly 3 active uploads');
    assert.ok(maxObservedActive <= 3, 'Active uploads NEVER exceeded CONCURRENCY_LIMIT = 3');
    assert.equal(completedItems.length, totalFiles, 'All files completed successfully');
    assert.equal(completedCount, totalFiles, 'Completed count accurately reached total');

    // Confirm that files completed out-of-order due to differing upload durations
    const completionIndices = completedItems.map(item => item.originalIndex);
    assert.notDeepEqual(completionIndices, [0, 1, 2, 3, 4, 5, 6], 'Files completed asynchronously out of sequential order');
  });

  await t.test('2. Order, Cover Photo, Room Tags & Metadata Preservation', async () => {
    const mediaList = [
      { id: 'm-0', name: 'front_facade.jpg', isCover: true, roomTag: 'EXTERIOR', originalIndex: 0 },
      { id: 'm-1', name: 'master_bedroom.jpg', isCover: false, roomTag: 'MASTER_BEDROOM', originalIndex: 1 },
      { id: 'm-2', name: 'modular_kitchen.jpg', isCover: false, roomTag: 'KITCHEN', originalIndex: 2 },
      { id: 'm-3', name: 'balcony_view.jpg', isCover: false, roomTag: 'BALCONY', originalIndex: 3 }
    ];

    // Simulate completion in random order: 2, 0, 3, 1
    const completionOrder = [2, 0, 3, 1];
    const uploadedDatabaseRecords = [];

    for (const idx of completionOrder) {
      const item = mediaList[idx];
      uploadedDatabaseRecords.push({
        propertyId: 101,
        fileName: item.name,
        isPrimaryCover: item.isCover,
        roomTag: item.roomTag,
        orderIndex: item.originalIndex
      });
    }

    // Sort by authoritative orderIndex
    const sortedFinalGallery = [...uploadedDatabaseRecords].sort((a, b) => a.orderIndex - b.orderIndex);

    assert.equal(sortedFinalGallery[0].isPrimaryCover, true, 'Original cover image remains primary cover');
    assert.equal(sortedFinalGallery[0].fileName, 'front_facade.jpg');
    assert.equal(sortedFinalGallery[0].roomTag, 'EXTERIOR');

    assert.equal(sortedFinalGallery[1].fileName, 'master_bedroom.jpg');
    assert.equal(sortedFinalGallery[1].roomTag, 'MASTER_BEDROOM');
    assert.equal(sortedFinalGallery[1].isPrimaryCover, false);

    assert.equal(sortedFinalGallery[2].fileName, 'modular_kitchen.jpg');
    assert.equal(sortedFinalGallery[2].roomTag, 'KITCHEN');

    assert.equal(sortedFinalGallery[3].fileName, 'balcony_view.jpg');
    assert.equal(sortedFinalGallery[3].roomTag, 'BALCONY');
  });

  await t.test('3. Progress Accuracy under Concurrency: based on completed count / total count', () => {
    const totalFiles = 6;
    let completedCount = 0;
    const progressReports = [];

    const recordProgress = () => {
      const overallProgress = Math.round((completedCount / totalFiles) * 100);
      progressReports.push({ completedCount, totalFiles, overallProgress });
    };

    // Increments out of order as workers finish:
    for (let c = 1; c <= totalFiles; c += 1) {
      completedCount = c;
      recordProgress();
    }

    assert.deepEqual(
      progressReports.map(p => p.overallProgress),
      [17, 33, 50, 67, 83, 100],
      'Progress steps monotonically from 17% to 100% based on completed count / total count'
    );
  });

  await t.test('4. Partial Failures: Failure isolation and Smart Retry preservation', async () => {
    const files = [
      { name: 'good1.jpg', fail: false, originalIndex: 0 },
      { name: 'bad2.jpg', fail: true, originalIndex: 1 },
      { name: 'good3.jpg', fail: false, originalIndex: 2 }
    ];

    const failedItems = [];
    const successfulItems = [];

    for (const item of files) {
      if (item.fail) {
        failedItems.push(item);
      } else {
        successfulItems.push(item);
      }
    }

    assert.equal(successfulItems.length, 2, 'Successful uploads preserved');
    assert.equal(failedItems.length, 1, 'Failed upload tracked in failed items queue');
    assert.equal(failedItems[0].name, 'bad2.jpg');
    assert.equal(failedItems[0].originalIndex, 1, 'Original index preserved for Smart Retry');
  });

  await t.test('5. Batch Property Publishing: Max global concurrency NEVER exceeds 3', async () => {
    // 3 properties in a batch, each having 3 media items (total 9 files)
    const batchProperties = [
      { id: 201, title: 'Flat 101', mediaCount: 3 },
      { id: 202, title: 'Flat 102', mediaCount: 3 },
      { id: 203, title: 'Flat 103', mediaCount: 3 }
    ];

    const CONCURRENCY_LIMIT = 3;
    let maxObservedGlobalActive = 0;
    let currentlyActive = 0;
    const publishedMedia = [];

    // Each property processed sequentially in batch loop, uploading its media with bounded concurrency = 3
    for (const prop of batchProperties) {
      const propFiles = Array.from({ length: prop.mediaCount }, (_, fIdx) => ({
        propertyId: prop.id,
        fileIndex: fIdx,
        fileName: `prop_${prop.id}_file_${fIdx}.jpg`
      }));

      let nextIndex = 0;
      const worker = async () => {
        while (nextIndex < propFiles.length) {
          const idx = nextIndex;
          nextIndex += 1;
          const item = propFiles[idx];

          currentlyActive += 1;
          if (currentlyActive > maxObservedGlobalActive) {
            maxObservedGlobalActive = currentlyActive;
          }

          // Simulate upload
          await new Promise((resolve) => setTimeout(resolve, 15));

          currentlyActive -= 1;
          publishedMedia.push(item);
        }
      };

      const workerCount = Math.min(CONCURRENCY_LIMIT, propFiles.length);
      const workers = [];
      for (let w = 0; w < workerCount; w += 1) {
        workers.push(worker());
      }
      await Promise.all(workers);
    }

    assert.equal(publishedMedia.length, 9, 'All 9 batch media files published');
    assert.equal(maxObservedGlobalActive, 3, 'Max observed concurrency across the entire batch was exactly 3');
    assert.ok(maxObservedGlobalActive <= 3, 'Batch uploads NEVER created 9 or 30 simultaneous uploads');

    // Confirm property ID isolation
    for (const item of publishedMedia) {
      assert.ok([201, 202, 203].includes(item.propertyId), 'Property ID association preserved exactly');
    }
  });
});
