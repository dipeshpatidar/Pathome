import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  appendDiscoveryProperties,
  classifyPropertyRefreshFailure,
  createPropertyRefreshCoordinator,
  decidePropertyRefreshCommit,
  preferNewestPropertySnapshot,
  propertyRefreshRequestIsCurrent,
  propertyMatchesDiscoveryFilters,
  reconcileDiscoveryProperties,
  reconcileActiveMediaIndex,
  reconcileRevalidatedProperties,
  settleWithConcurrency
} from '../utils/propertyRefreshState.ts';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const read = relative => readFile(path.join(root, relative), 'utf8');

const media = (url, type = 'IMAGE') => ({ url, type });

const importMutant = async (relative, transform) => {
  const source = await read(relative);
  const changed = transform(source);
  assert.notEqual(changed, source, 'the negative control must change the intended guard');
  const directory = await mkdtemp(path.join(tmpdir(), 'pathome-freshness-mutant-'));
  const file = path.join(directory, `mutant-${Date.now()}-${Math.random()}.ts`);
  await writeFile(file, changed, 'utf8');
  try {
    return await import(`${pathToFileURL(file).href}?v=${Date.now()}`);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
};

test('an unchanged refresh preserves the exact selected media by stable public URL', () => {
  const before = [media('a.jpg'), media('b.jpg'), media('tour.mp4', 'VIDEO')];
  const after = [media('a.jpg'), media('b.jpg'), media('tour.mp4', 'VIDEO')];
  assert.equal(reconcileActiveMediaIndex(before, after, 2), 2);
});

test('a reordered gallery keeps the selected resource and a removed resource falls back safely', () => {
  const before = [media('a.jpg'), media('b.jpg'), media('c.jpg')];
  const reordered = [media('c.jpg'), media('a.jpg'), media('b.jpg')];
  assert.equal(reconcileActiveMediaIndex(before, reordered, 1), 2);

  const removed = [media('c.jpg'), media('b.jpg')];
  assert.equal(reconcileActiveMediaIndex(before, removed, 1), 1);
  assert.equal(reconcileActiveMediaIndex(before, [], 1), 0);
});

test('only authoritative unavailability is treated as archived', () => {
  assert.equal(classifyPropertyRefreshFailure(404), 'unavailable');
  assert.equal(classifyPropertyRefreshFailure(500), 'transient');
  assert.equal(classifyPropertyRefreshFailure(0), 'transient');
  assert.equal(classifyPropertyRefreshFailure(403), 'denied');
});

test('revalidation updates every displayed identity and removes only proven 404 identities', () => {
  const current = [
    { id: 1, title: 'Page zero' },
    { id: 2, title: 'Later card' },
    { id: 3, title: 'Transient failure' },
    { id: 4, title: 'Archived' }
  ];
  const result = reconcileRevalidatedProperties(
    current,
    new Map([[1, { id: 1, title: 'Updated' }], [2, { id: 2, title: 'Later card' }]]),
    new Set([4])
  );
  assert.deepEqual(result.map(item => item.id), [1, 2, 3]);
  assert.equal(result[0].title, 'Updated');
});

test('a revalidated card must continue matching the active filters', () => {
  const property = {
    title: 'Family home', sector: 'Vijay Nagar', city: 'Indore', description: 'Quiet',
    bhk: '2BHK', propertyType: 'FLAT', furnishingStatus: 'SEMI_FURNISHED',
    monthlyRent: 30000, listingType: 'RENT'
  };
  assert.equal(propertyMatchesDiscoveryFilters(property,
    { city: 'Indore', sector: 'Vijay Nagar', maxRent: 31000, furnishing: 'FURNISHED', rentalOnly: true }), true);
  assert.equal(propertyMatchesDiscoveryFilters(property,
    { city: 'Indore', sector: 'Vijay Nagar', maxRent: 29000 }), false);
  assert.equal(propertyMatchesDiscoveryFilters(property,
    { city: 'Indore', sector: 'Vijay Nagar', propertyType: 'HOUSE' }), false);
});

test('detail refreshes do not reset selected media and Home revalidates later-loaded cards', async () => {
  const detail = await read('frontend-web/src/components/PublicPropertyDetail.tsx');
  const home = await read('frontend-web/src/components/Home.tsx');
  assert.doesNotMatch(detail, /getPublicProperty\(propertyId\)[\s\S]{0,250}setActiveMedia\(0\)/);
  assert.match(detail, /classifyPropertyRefreshFailure/);
  assert.match(detail, /reconcileActiveMediaIndex/);
  assert.match(home, /decidePropertyRefreshCommit/);
  assert.match(home, /propertiesRef\.current\.map\(item => item\.id\)/);
  assert.doesNotMatch(home, /new Map\(result\.properties\.map\(item => \[item\.id, item\]\)\)/);
  assert.match(home, /createPropertyRefreshCoordinator<Property>/);
  assert.match(home, /settleWithConcurrency\(ids, 6/);
  assert.match(home, /currentOperationsSessionGeneration/);
  assert.match(home, /homeRefreshIsCurrent/);
});

test('shared refresh ordering retains the newest listingVersion in either completion order', () => {
  for (const versions of [[1, 2], [2, 1]]) {
    const order = createPropertyRefreshCoordinator();
    const first = order.begin(42);
    const second = order.begin(42);
    const accepted = versions.map((version, index) =>
      order.accept(42, index === 0 ? first : second, { id: 42, listingVersion: version }));
    assert.deepEqual(accepted, versions[0] < versions[1] ? [true, true] : [true, false]);
  }
});

test('a stale 404 cannot remove a newer successful fact and the newest 404 can', () => {
  const order = createPropertyRefreshCoordinator();
  const older = order.begin(42);
  const newer = order.begin(42);
  assert.equal(order.accept(42, newer, { id: 42, listingVersion: 2 }), true);
  assert.equal(order.unavailable(42, older), false);

  const staleDiscovery = order.begin(42);
  const archive = order.begin(42);
  assert.equal(order.unavailable(42, archive), true);
  assert.equal(order.accept(42, staleDiscovery, { id: 42, listingVersion: 2 }), false);
  assert.equal(order.isUnavailable(42), true);
});

test('discovery, restore and later-page results cannot overwrite a newer displayed Listing', () => {
  const displayed = [{ id: 42, title: 'Version two', listingVersion: 2 }];
  const stale = [{ id: 42, title: 'Version one', listingVersion: 1 }];
  assert.deepEqual(reconcileDiscoveryProperties(displayed, stale), displayed);
  assert.equal(preferNewestPropertySnapshot(displayed[0], stale[0]).title, 'Version two');

  const appended = appendDiscoveryProperties(displayed, [
    { id: 42, title: 'Version one', listingVersion: 1 },
    { id: 43, title: 'Later card', listingVersion: 1 },
    { id: 43, title: 'Later card newer', listingVersion: 2 }
  ]);
  assert.deepEqual(appended, [
    displayed[0],
    { id: 43, title: 'Later card newer', listingVersion: 2 }
  ]);
});

test('successful and unavailable completions obey displayed version and removal tombstones', () => {
  const order = createPropertyRefreshCoordinator();
  const eventStarted = order.begin(42);
  const discovery = order.begin(42);
  assert.equal(order.accept(42, discovery, { id: 42, listingVersion: 2 }), true);
  assert.equal(order.accept(42, eventStarted, { id: 42, listingVersion: 1 }), false);

  const archive = order.begin(42);
  assert.equal(order.unavailable(42, archive), true);
  assert.equal(order.accept(42, eventStarted, { id: 42, listingVersion: 1 }), false);
  assert.equal(order.isUnavailable(42), true);
});

test('missing versions use the latest request and never replace a comparable displayed version', () => {
  const order = createPropertyRefreshCoordinator();
  const older = order.begin(42);
  const newer = order.begin(42);
  assert.equal(order.accept(42, older, { id: 42, listingVersion: null }), false);
  assert.equal(order.accept(42, newer, { id: 42, listingVersion: null }), true);
  assert.equal(preferNewestPropertySnapshot(
    { id: 42, title: 'Versioned', listingVersion: 3 },
    { id: 42, title: 'Unversioned' }
  ).title, 'Versioned');
});

test('version boundaries admit only comparable newer facts and valid equal versions', () => {
  const displayed = { id: 42, title: 'Displayed v2', listingVersion: 2 };
  const cases = [
    [{ listingVersion: 1 }, 'Displayed v2'],
    [{ listingVersion: 2 }, 'Equal v2'],
    [{ listingVersion: 3 }, 'Equal v3'],
    [{ listingVersion: 0 }, 'Displayed v2'],
    [{ listingVersion: null }, 'Displayed v2'],
    [{}, 'Displayed v2'],
    [{ listingVersion: -1 }, 'Displayed v2'],
    [{ listingVersion: 1.5 }, 'Displayed v2']
  ];
  for (const [incoming, expected] of cases) {
    assert.equal(preferNewestPropertySnapshot(displayed, {
      id: 42, title: incoming.listingVersion === undefined ? 'Missing' : `Equal v${incoming.listingVersion}`,
      ...incoming
    }).title, expected);
  }
  assert.equal(preferNewestPropertySnapshot(
    { id: 42, title: 'Unversioned current', listingVersion: null },
    { id: 42, title: 'Comparable new', listingVersion: 3 }
  ).title, 'Comparable new');
  assert.equal(preferNewestPropertySnapshot(displayed,
    { id: 999, title: 'Wrong identity', listingVersion: 9 }).title, 'Displayed v2');
  assert.equal(preferNewestPropertySnapshot(displayed,
    { id: 0, title: 'Malformed identity', listingVersion: 9 }).title, 'Displayed v2');
});

test('the functional state commit rechecks React current facts and request generation', () => {
  const current = [{ id: 42, title: 'Current v4', listingVersion: 4 }];
  const request = { generation: 7, requestId: 12, sessionGeneration: 3 };
  const active = { ...request };
  const older = decidePropertyRefreshCommit({
    current,
    incoming: [{ id: 42, title: 'Late v3', listingVersion: 3 }],
    mode: 'revalidate',
    request,
    active
  });
  assert.equal(older[0].title, 'Current v4');

  const unversioned = decidePropertyRefreshCommit({
    current,
    incoming: [{ id: 42, title: 'Unproven late', listingVersion: null }],
    mode: 'revalidate',
    request,
    active
  });
  assert.equal(unversioned[0].title, 'Current v4');

  const replaced = decidePropertyRefreshCommit({
    current,
    incoming: [
      { id: 42, title: 'Duplicate v5', listingVersion: 5 },
      { id: 42, title: 'Newest duplicate v6', listingVersion: 6 },
      { id: 43, title: 'New page card', listingVersion: 1 }
    ],
    mode: 'replace',
    request,
    active
  });
  assert.deepEqual(replaced.map(item => [item.id, item.title]), [
    [42, 'Newest duplicate v6'],
    [43, 'New page card']
  ]);

  for (const stale of [
    { ...active, generation: active.generation + 1 },
    { ...active, requestId: active.requestId + 1 },
    { ...active, sessionGeneration: active.sessionGeneration + 1 }
  ]) {
    assert.equal(propertyRefreshRequestIsCurrent(request, stale), false);
    assert.strictEqual(decidePropertyRefreshCommit({
      current,
      incoming: [{ id: 42, title: 'Obsolete', listingVersion: 9 }],
      mode: 'replace',
      request,
      active: stale
    }), current);
  }
});

test('an unversioned refresh retains a comparable card while changed filters can remove accepted facts', () => {
  const current = [{ id: 42, title: 'Versioned', listingVersion: 2, monthlyRent: 25_000 }];
  const request = { generation: 1, requestId: 1, sessionGeneration: 1 };
  const retained = decidePropertyRefreshCommit({
    current,
    incoming: [{ id: 42, title: 'Unversioned', listingVersion: null, monthlyRent: 12_345 }],
    mode: 'revalidate',
    request,
    active: request,
    matches: property => property.monthlyRent <= 20_000
  });
  assert.equal(retained[0].monthlyRent, 25_000,
    'filter membership cannot erase a card whose incoming facts were not committed');
});

test('a held append cannot reinsert a Listing excluded by its newer authoritative fact', () => {
  const order = createPropertyRefreshCoordinator();
  const request = { generation: 1, requestId: 2, sessionGeneration: 1 };
  const pageSequence = order.begin(90);
  assert.equal(order.accept(90, pageSequence, {
    id: 90, title: 'Page snapshot v2', listingVersion: 2, monthlyRent: 25_000
  }), true);

  const eventSequence = order.begin(90);
  assert.equal(order.accept(90, eventSequence, {
    id: 90, title: 'Newer v3 now outside rent filter', listingVersion: 3, monthlyRent: 40_000
  }), true);
  const afterEvent = decidePropertyRefreshCommit({
    current: [{ id: 90, title: 'Page snapshot v2', listingVersion: 2, monthlyRent: 25_000 }],
    incoming: [{ id: 90, title: 'Newer v3 now outside rent filter', listingVersion: 3, monthlyRent: 40_000 }],
    mode: 'revalidate',
    request,
    active: request,
    matches: property => property.monthlyRent <= 30_000,
    knowledge: order
  });
  assert.deepEqual(afterEvent, []);

  const releasedAppend = decidePropertyRefreshCommit({
    current: [{ id: 91, title: 'Unrelated valid card', listingVersion: 2, monthlyRent: 20_000 }],
    incoming: [{ id: 90, title: 'Held page snapshot v2', listingVersion: 2, monthlyRent: 25_000 }],
    mode: 'append',
    request,
    active: request,
    matches: property => property.monthlyRent <= 30_000,
    knowledge: order
  });
  assert.deepEqual(releasedAppend.map(property => property.id), [91],
    'the rejected page fact must not return and must not remove the safe unrelated card');

  const unversionedAppend = decidePropertyRefreshCommit({
    current: releasedAppend,
    incoming: [{ id: 90, title: 'Unversioned held page', listingVersion: null, monthlyRent: 25_000 }],
    mode: 'append',
    request,
    active: request,
    matches: property => property.monthlyRent <= 30_000,
    knowledge: order
  });
  assert.deepEqual(unversionedAppend.map(property => property.id), [91]);

  const deduplicated = decidePropertyRefreshCommit({
    current: [
      { id: 91, title: 'Duplicate v1', listingVersion: 1, monthlyRent: 20_000 },
      { id: 91, title: 'Duplicate v2', listingVersion: 2, monthlyRent: 20_000 }
    ],
    incoming: [],
    mode: 'append',
    request,
    active: request,
    knowledge: order
  });
  assert.deepEqual(deduplicated.map(property => [property.id, property.listingVersion]), [[91, 2]]);

  const newerSequence = order.begin(90);
  assert.equal(order.accept(90, newerSequence, {
    id: 90, title: 'Matching v4 returns', listingVersion: 4, monthlyRent: 28_000
  }), true);
  const returned = decidePropertyRefreshCommit({
    current: releasedAppend,
    incoming: [{ id: 90, title: 'Matching v4 returns', listingVersion: 4, monthlyRent: 28_000 }],
    mode: 'revalidate',
    request,
    active: request,
    matches: property => property.monthlyRent <= 30_000,
    allowInsert: true,
    knowledge: order
  });
  assert.deepEqual(returned.map(property => [property.id, property.listingVersion]), [[91, 2], [90, 4]]);
});

test('a batched 404 cannot outlive newer coordinator knowledge and its matching v4 returns once', () => {
  const order = createPropertyRefreshCoordinator();
  const request = { generation: 1, requestId: 1, sessionGeneration: 1 };
  const displayed = [
    { id: 90, title: 'Current v2', listingVersion: 2, monthlyRent: 25_000 },
    { id: 91, title: 'Unrelated safe card', listingVersion: 2, monthlyRent: 20_000 }
  ];

  const unavailableSequence = order.begin(90);
  assert.equal(order.unavailable(90, unavailableSequence), true);
  const onlineSequence = order.begin(90);
  assert.equal(order.accept(90, onlineSequence, {
    id: 90, title: 'Matching v4', listingVersion: 4, monthlyRent: 28_000
  }), true);
  assert.equal(order.isUnavailable(90), false);

  const queuedRemoval = decidePropertyRefreshCommit({
    current: displayed,
    incoming: [],
    mode: 'revalidate',
    request,
    active: request,
    unavailable: new Set([90]),
    matches: property => property.monthlyRent <= 30_000,
    knowledge: order
  });
  assert.deepEqual(queuedRemoval, displayed,
    'the current coordinator must overrule an obsolete unavailable set');

  const vanishedByEarlierUpdater = [];
  const restored = decidePropertyRefreshCommit({
    current: vanishedByEarlierUpdater,
    incoming: [{
      id: 90, title: 'Matching v4', listingVersion: 4, monthlyRent: 28_000
    }],
    mode: 'revalidate',
    request,
    active: request,
    matches: property => property.monthlyRent <= 30_000,
    allowInsert: true,
    insertIds: new Set([90, 91]),
    knowledge: order
  });
  assert.deepEqual(restored.map(property => [
    property.id, property.listingVersion, property.monthlyRent
  ]), [[90, 4, 28_000]]);

  const unrequested = decidePropertyRefreshCommit({
    current: [],
    incoming: [{
      id: 90, title: 'Matching v4', listingVersion: 4, monthlyRent: 28_000
    }, {
      id: 999, title: 'Unrequested discovery row', listingVersion: 9, monthlyRent: 1
    }],
    mode: 'revalidate',
    request,
    active: request,
    matches: () => true,
    allowInsert: true,
    insertIds: new Set([90, 91]),
    knowledge: order
  });
  assert.deepEqual(unrequested.map(property => property.id), [90],
    'batch reinsertion must remain restricted to its captured loaded IDs');
});

test('the newest accepted fact and tombstone participate in commit-time membership', () => {
  const order = createPropertyRefreshCoordinator();
  const request = { generation: 1, requestId: 1, sessionGeneration: 1 };
  const successful = order.begin(42);
  assert.equal(order.accept(42, successful, {
    id: 42, title: 'Authoritative v3', listingVersion: 3, monthlyRent: 40_000
  }), true);
  const archived = order.begin(42);
  assert.equal(order.unavailable(42, archived), true);

  const committed = decidePropertyRefreshCommit({
    current: [{ id: 43, title: 'Safe card', listingVersion: 1, monthlyRent: 20_000 }],
    incoming: [{
      id: 42, title: 'Obsolete in-filter v2', listingVersion: 2, monthlyRent: 25_000
    }],
    mode: 'append',
    request,
    active: request,
    matches: property => property.monthlyRent <= 30_000,
    knowledge: order
  });
  assert.deepEqual(committed.map(property => property.id), [43]);
});

test('a versioned event can reinsert a previously tracked card after an archive', () => {
  const request = { generation: 1, requestId: 1, sessionGeneration: 1 };
  const inserted = decidePropertyRefreshCommit({
    current: [{ id: 43, title: 'Other card', listingVersion: 1 }],
    incoming: [{ id: 42, title: 'Republished v5', listingVersion: 5, monthlyRent: 25_000 }],
    mode: 'revalidate',
    request,
    active: request,
    allowInsert: true,
    matches: property => property.monthlyRent <= 30_000
  });
  assert.deepEqual(inserted.map(item => item.id), [43, 42]);
});

test('negative controls reproduce unversioned overwrite and obsolete-generation acceptance', async () => {
  const freshnessMutant = await importMutant(
    'frontend-web/src/utils/propertyRefreshState.ts',
    source => source.replace(
      'if (incomingVersion === null) return displayedVersion === null ? incoming as T : displayed as T;',
      'if (incomingVersion === null) return incoming as T;'
    )
  );
  assert.equal(freshnessMutant.preferNewestPropertySnapshot(
    { id: 42, title: 'Versioned', listingVersion: 2 },
    { id: 42, title: 'Unversioned overwrite', listingVersion: null }
  ).title, 'Unversioned overwrite');

  const generationMutant = await importMutant(
    'frontend-web/src/utils/propertyRefreshState.ts',
    source => source.replace(
      'if (input.owned === false || !propertyRefreshRequestIsCurrent(input.request, input.active)) {',
      'if (false) {'
    )
  );
  const current = [{ id: 42, title: 'Current', listingVersion: 4 }];
  assert.equal(generationMutant.decidePropertyRefreshCommit({
    current,
    incoming: [{ id: 42, title: 'Obsolete commit', listingVersion: 5 }],
    mode: 'replace',
    request: { generation: 1, requestId: 1, sessionGeneration: 1 },
    active: { generation: 2, requestId: 2, sessionGeneration: 2 }
  })[0].title, 'Obsolete commit');
});

test('removing newest-fact reconciliation reinserts the exact stale page row', async () => {
  const knowledgeMutant = await importMutant(
    'frontend-web/src/utils/propertyRefreshState.ts',
    source => source.replace(
      'const acceptedFact = (property: T): T => input.knowledge?.latest(property.id) ?? property;',
      'const acceptedFact = (property: T): T => property;'
    )
  );
  const order = knowledgeMutant.createPropertyRefreshCoordinator();
  const request = { generation: 1, requestId: 1, sessionGeneration: 1 };
  order.accept(90, order.begin(90), {
    id: 90, title: 'Page v2', listingVersion: 2, monthlyRent: 25_000
  });
  order.accept(90, order.begin(90), {
    id: 90, title: 'Newer v3', listingVersion: 3, monthlyRent: 40_000
  });
  const inserted = knowledgeMutant.decidePropertyRefreshCommit({
    current: [{ id: 91, title: 'Other', listingVersion: 1, monthlyRent: 20_000 }],
    incoming: [{ id: 90, title: 'Page v2', listingVersion: 2, monthlyRent: 25_000 }],
    mode: 'append',
    request,
    active: request,
    matches: property => property.monthlyRent <= 30_000,
    knowledge: order
  });
  assert.deepEqual(inserted.map(property => property.id), [91, 90],
    'the exact stale row reappears when commit-time high-water knowledge is bypassed');
});

test('C1-R1 negative controls remove current availability authority and bounded reinsertion', async () => {
  const request = { generation: 1, requestId: 1, sessionGeneration: 1 };
  const availabilityMutant = await importMutant(
    'frontend-web/src/utils/propertyRefreshState.ts',
    source => source.replace(
      'input.knowledge\n      ? input.knowledge.isUnavailable(listingId)\n      : unavailable.has(listingId);',
      'unavailable.has(listingId) || input.knowledge?.isUnavailable(listingId) === true;'
    )
  );
  const mutantOrder = availabilityMutant.createPropertyRefreshCoordinator();
  mutantOrder.unavailable(90, mutantOrder.begin(90));
  mutantOrder.accept(90, mutantOrder.begin(90), {
    id: 90, title: 'Current v4', listingVersion: 4, monthlyRent: 28_000
  });
  const removedByObsoleteSet = availabilityMutant.decidePropertyRefreshCommit({
    current: [{ id: 90, title: 'Current v2', listingVersion: 2, monthlyRent: 25_000 }],
    incoming: [],
    mode: 'revalidate',
    request,
    active: request,
    unavailable: new Set([90]),
    knowledge: mutantOrder
  });
  assert.deepEqual(removedByObsoleteSet, [],
    'restoring the obsolete-set union must fail the current-availability assertion');

  const noReinsertionMutant = await importMutant(
    'frontend-web/src/utils/propertyRefreshState.ts',
    source => source.replace(
      'if (!input.allowInsert) return next;',
      'return next;'
    )
  );
  const noReinsertionOrder = noReinsertionMutant.createPropertyRefreshCoordinator();
  noReinsertionOrder.accept(90, noReinsertionOrder.begin(90), {
    id: 90, title: 'Current v4', listingVersion: 4, monthlyRent: 28_000
  });
  const missingAfterQueuedRemoval = noReinsertionMutant.decidePropertyRefreshCommit({
    current: [],
    incoming: [{ id: 90, title: 'Current v4', listingVersion: 4, monthlyRent: 28_000 }],
    mode: 'revalidate',
    request,
    active: request,
    allowInsert: true,
    insertIds: new Set([90]),
    knowledge: noReinsertionOrder
  });
  assert.deepEqual(missingAfterQueuedRemoval, [],
    'removing bounded reinsertion must fail the exact batched-membership assertion');

  const unboundedMutant = await importMutant(
    'frontend-web/src/utils/propertyRefreshState.ts',
    source => source.replace(
      'if (input.insertIds && !input.insertIds.has(item.id)) return;',
      'if (false) return;'
    )
  );
  const unbounded = unboundedMutant.decidePropertyRefreshCommit({
    current: [],
    incoming: [{
      id: 90, title: 'Requested', listingVersion: 4, monthlyRent: 28_000
    }, {
      id: 999, title: 'Unrequested', listingVersion: 9, monthlyRent: 1
    }],
    mode: 'revalidate',
    request,
    active: request,
    allowInsert: true,
    insertIds: new Set([90])
  });
  assert.deepEqual(unbounded.map(property => property.id), [90, 999],
    'removing the captured-ID scope must fail the no-unrequested-row assertion');
});

test('refresh ordering resets on account, filter, and view generation replacement', () => {
  const accountA = createPropertyRefreshCoordinator();
  const aSequence = accountA.begin(42);
  assert.equal(accountA.accept(42, aSequence, { id: 42, listingVersion: 9 }), true);

  const accountB = createPropertyRefreshCoordinator();
  const bSequence = accountB.begin(42);
  assert.equal(accountB.accept(42, bSequence, { id: 42, listingVersion: 1 }), true);
  assert.equal(accountA.accept(42, aSequence, { id: 42, listingVersion: 9 }), true);
});

test('bounded revalidation reaches every later-loaded identity without a request burst', async () => {
  let active = 0;
  let peak = 0;
  const values = Array.from({ length: 23 }, (_, index) => index + 1);
  const outcomes = await settleWithConcurrency(values, 6, async value => {
    active++;
    peak = Math.max(peak, active);
    await new Promise(resolve => setTimeout(resolve, 2));
    active--;
    return value * 2;
  });
  assert.equal(peak, 6);
  assert.deepEqual(outcomes.map(outcome => outcome.value), values.map(value => value * 2));
});

test('bounded revalidation isolates partial failures across zero, one and many loaded IDs', async () => {
  assert.deepEqual(await settleWithConcurrency([], 6, async value => value), []);
  assert.deepEqual(
    (await settleWithConcurrency([42], 6, async value => value + 1)).map(outcome => outcome.value),
    [43]
  );

  let active = 0;
  let peak = 0;
  const outcomes = await settleWithConcurrency(
    Array.from({ length: 9 }, (_, index) => index + 1),
    6,
    async value => {
      active += 1;
      peak = Math.max(peak, active);
      await new Promise(resolve => setTimeout(resolve, 2));
      active -= 1;
      if (value === 3 || value === 7) throw new Error(`transient-${value}`);
      return value;
    }
  );
  assert.equal(peak, 6);
  assert.deepEqual(outcomes.map(outcome => outcome.status), [
    'fulfilled', 'fulfilled', 'rejected', 'fulfilled', 'fulfilled',
    'fulfilled', 'rejected', 'fulfilled', 'fulfilled'
  ]);
  assert.deepEqual(
    outcomes.filter(outcome => outcome.status === 'fulfilled').map(outcome => outcome.value),
    [1, 2, 4, 5, 6, 8, 9]
  );
});

test('final publication dispatch rechecks dirty and readiness state', async () => {
  const editor = await read('frontend-web/src/components/backoffice/BackofficePropertyEditor.tsx');
  assert.match(editor, /if \(dirty \|\| !revision\.preview\.ready\)/);
  assert.match(editor, /operationIdForIntent/);
  assert.match(editor, /disabled=\{Boolean\(busy\) \|\| dirty \|\| !revision\.preview\.ready \|\| !publishReason\.trim\(\)\}/);
});
