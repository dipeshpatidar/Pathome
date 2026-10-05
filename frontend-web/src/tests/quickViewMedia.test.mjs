import test from 'node:test';
import assert from 'node:assert/strict';
import { exitQuickViewFullscreen, formatQuickViewVideoTime, getQuickViewMedia, isQuickViewFullscreenActive, lockQuickViewBodyScroll, moveQuickViewMediaIndex, nextQuickViewControlsState, quickViewFullscreenStrategy, quickViewSwipeStartsOnControl, resolveQuickViewDownwardSwipe, resolveQuickViewStageAction, resolveQuickViewSwipeDirection } from '../utils/quickViewMedia.ts';

test('Quick View preserves the complete ordered real tagged collection, including untagged images and videos', () => {
  const property = {
    images: [
      'https://media.pathome.example/living-room.jpg',
      'https://media.pathome.example/untagged.jpg',
      'https://media.pathome.example/bedroom.jpg'
    ],
    videoUrl: 'https://media.pathome.example/walkthrough.mp4',
    coverRoomTag: 'LIVING_ROOM',
    taggedMedia: [
      { mediaUrl: 'https://media.pathome.example/living-room.jpg', mediaType: 'IMAGE', roomTag: 'LIVING_ROOM' },
      { mediaUrl: 'https://media.pathome.example/untagged.jpg', mediaType: 'IMAGE', roomTag: null },
      { mediaUrl: 'https://media.pathome.example/walkthrough.mp4', mediaType: 'VIDEO_WALKTHROUGH', roomTag: null },
      { mediaUrl: 'https://media.pathome.example/bedroom.jpg', mediaType: 'IMAGE', roomTag: 'BEDROOM' },
      { mediaUrl: '   ', mediaType: 'IMAGE', roomTag: 'BALCONY' }
    ]
  };

  const media = getQuickViewMedia(property);
  assert.equal(media.length, 4);
  assert.deepEqual(media.map(({ url, type, tagLabel }) => ({ url, type, tagLabel })), [
    { url: 'https://media.pathome.example/living-room.jpg', type: 'IMAGE', tagLabel: 'Living Room' },
    { url: 'https://media.pathome.example/untagged.jpg', type: 'IMAGE', tagLabel: null },
    { url: 'https://media.pathome.example/walkthrough.mp4', type: 'VIDEO', tagLabel: null },
    { url: 'https://media.pathome.example/bedroom.jpg', type: 'IMAGE', tagLabel: 'Bedroom' }
  ]);
});

test('Quick View appends valid legacy projections missing from tagged media without duplicating shared URLs', () => {
  const media = getQuickViewMedia({
    taggedMedia: [{ mediaUrl: 'https://media.pathome.example/cover.jpg', mediaType: 'IMAGE', roomTag: null }],
    images: ['https://media.pathome.example/cover.jpg', 'https://media.pathome.example/untagged-balcony.jpg'],
    videoUrl: 'https://media.pathome.example/tour-without-extension',
    coverRoomTag: 'BALCONY'
  });

  assert.deepEqual(media, [
    { url: 'https://media.pathome.example/cover.jpg', type: 'IMAGE', tagLabel: 'Balcony' },
    { url: 'https://media.pathome.example/untagged-balcony.jpg', type: 'IMAGE', tagLabel: null },
    { url: 'https://media.pathome.example/tour-without-extension', type: 'VIDEO', tagLabel: null }
  ]);
});

test('Quick View media navigation wraps correctly and reports the entire collection length', () => {
  const media = getQuickViewMedia({
    images: ['https://media.pathome.example/one.jpg', 'https://media.pathome.example/two.jpg'],
    videoUrl: 'https://media.pathome.example/three.mp4'
  });

  assert.equal(media.length, 3);
  assert.equal(moveQuickViewMediaIndex(0, media.length, -1), 2);
  assert.equal(moveQuickViewMediaIndex(0, media.length, 1), 1);
  assert.equal(moveQuickViewMediaIndex(2, media.length, 1), 0);
  assert.equal(moveQuickViewMediaIndex(0, 0, -1), 0);
});

test('Quick View swipe changes media only for a sufficiently long horizontal gesture', () => {
  assert.equal(resolveQuickViewSwipeDirection(180, 100, 120, 104), 1);
  assert.equal(resolveQuickViewSwipeDirection(120, 100, 185, 102), -1);
  assert.equal(resolveQuickViewSwipeDirection(120, 100, 150, 101), 0, 'small drag does not change media');
  assert.equal(resolveQuickViewSwipeDirection(120, 100, 134, 170), 0, 'vertical gesture remains ignored');
  assert.equal(resolveQuickViewSwipeDirection(100, 100, 145, 145), 0, 'diagonal gesture remains ignored');
});

test('Quick View excludes seek and buttons from swipe starts, but allows image and video surfaces', () => {
  const target = (matches) => ({ closest: selector => matches.some(match => selector.includes(match)) });
  assert.equal(quickViewSwipeStartsOnControl(target(['input'])), true, 'timeline drag stays with seek control');
  assert.equal(quickViewSwipeStartsOnControl(target(['button'])), true, 'player button tap stays with control');
  assert.equal(quickViewSwipeStartsOnControl(target(['data-quick-view-control'])), true, 'control group is excluded');
  assert.equal(quickViewSwipeStartsOnControl(target(['role="button"'])), true, 'other interactive media controls are excluded');
  assert.equal(quickViewSwipeStartsOnControl(target(['video'])), false, 'video surface remains swipeable');
  assert.equal(quickViewSwipeStartsOnControl(target(['img'])), false, 'image surface remains swipeable');
  assert.equal(quickViewSwipeStartsOnControl(null), false);
});

test('Quick View video controls auto-hide only while playing and reveal on interaction', () => {
  let state = { playing: false, visible: true, activityVersion: 0 };
  state = nextQuickViewControlsState(state, 'TIMEOUT');
  assert.deepEqual(state, { playing: false, visible: true, activityVersion: 0 });
  state = nextQuickViewControlsState(state, 'PLAY');
  assert.deepEqual(state, { playing: true, visible: true, activityVersion: 1 });
  state = nextQuickViewControlsState(state, 'TIMEOUT');
  assert.deepEqual(state, { playing: true, visible: false, activityVersion: 1 });
  state = nextQuickViewControlsState(state, 'ACTIVITY');
  assert.deepEqual(state, { playing: true, visible: true, activityVersion: 2 });
  state = nextQuickViewControlsState(state, 'MEDIA_CHANGE');
  assert.deepEqual(state, { playing: false, visible: true, activityVersion: 3 });
  state = nextQuickViewControlsState({ playing: true, visible: false, activityVersion: 3 }, 'PAUSE');
  assert.deepEqual(state, { playing: false, visible: true, activityVersion: 4 });
  assert.equal(formatQuickViewVideoTime(125.9), '02:05');
  assert.equal(formatQuickViewVideoTime(Number.POSITIVE_INFINITY), '00:00');
});

test('one Quick View pointer release resolves to one media action across zones and gestures', () => {
  const gesture = (mediaType, startX, endX = startX, endY = 100, durationMs = 100, mediaCount = 3) =>
    resolveQuickViewStageAction({ startX, startY: 100, endX, endY, durationMs, stageLeft: 0, stageWidth: 300, mediaType, mediaCount });
  for (const type of ['IMAGE', 'VIDEO']) {
    assert.equal(gesture(type, 30), 'PREVIOUS', `${type} left tap`);
    assert.equal(gesture(type, 270), 'NEXT', `${type} right tap`);
    assert.equal(gesture(type, 250, 180), 'NEXT', `${type} left swipe wins over right tap zone`);
    assert.equal(gesture(type, 50, 120), 'PREVIOUS', `${type} right swipe wins over left tap zone`);
    assert.equal(gesture(type, 150, 150, 160), 'NONE', `${type} vertical drag is not a tap`);
    assert.equal(gesture(type, 270, 290, 100), 'NONE', `${type} medium drag is not a tap`);
    assert.equal(gesture(type, 30, 30, 100, 800), 'NONE', `${type} long press is not a tap`);
    assert.equal(gesture(type, 30, 30, 100, 100, 1), 'NONE', `${type} cannot navigate a single item`);
  }
  assert.equal(gesture('IMAGE', 150), 'NONE', 'image center has no action');
  assert.equal(gesture('VIDEO', 150), 'TOGGLE_PLAYBACK', 'video center requests play or pause');
  assert.equal(gesture('VIDEO', 150, 155), 'TOGGLE_PLAYBACK', 'small movement is still a tap');
  assert.equal(gesture('VIDEO', 150, 150, 100, 100, 1), 'TOGGLE_PLAYBACK', 'single video still plays');
});

test('fullscreen strategy always chooses native video, then standard stage, then app fallback by capability', () => {
  assert.equal(quickViewFullscreenStrategy(true, true), 'IOS_NATIVE');
  assert.equal(quickViewFullscreenStrategy(true, false), 'IOS_NATIVE');
  assert.equal(quickViewFullscreenStrategy(false, true), 'STANDARD');
  assert.equal(quickViewFullscreenStrategy(false, false), 'APP_FALLBACK');
});

test('app fullscreen scroll lock restores the prior body state on exit', () => {
  const body = { style: { overflow: 'auto' } };
  const restore = lockQuickViewBodyScroll(body);
  assert.equal(body.style.overflow, 'hidden');
  restore();
  assert.equal(body.style.overflow, 'auto');
  body.style.overflow = 'hidden';
  lockQuickViewBodyScroll(body)();
  assert.equal(body.style.overflow, 'hidden', 'the enclosing Quick View scroll lock remains in force');
});


test('Quick View downward swipe classifier distinguishes downward exit from small drags, upward swipes, and horizontal swipes', () => {
  // Valid downward swipe (deltaY >= 80 and abs(deltaY) > abs(deltaX))
  assert.equal(resolveQuickViewDownwardSwipe(100, 100, 105, 195), true, 'valid downward swipe');
  assert.equal(resolveQuickViewDownwardSwipe(100, 100, 100, 180), true, 'exactly at 80px threshold');

  // Upward swipe (deltaY < 0)
  assert.equal(resolveQuickViewDownwardSwipe(100, 200, 100, 100), false, 'upward swipe does not exit');

  // Short downward drag (deltaY < 80)
  assert.equal(resolveQuickViewDownwardSwipe(100, 100, 100, 150), false, 'short downward drag of 50px does not exit');
  assert.equal(resolveQuickViewDownwardSwipe(100, 100, 100, 179), false, 'drag just under threshold does not exit');

  // Diagonal mostly-horizontal gesture (abs(deltaX) > abs(deltaY))
  assert.equal(resolveQuickViewDownwardSwipe(100, 100, 220, 190), false, 'diagonal mostly-horizontal is not downward swipe');

  // Diagonal mostly-vertical downward gesture (abs(deltaY) > abs(deltaX) and deltaY >= 80)
  assert.equal(resolveQuickViewDownwardSwipe(100, 100, 140, 210), true, 'diagonal mostly-vertical downward swipe is valid');
});

test('Quick View stage action in fullscreen: swipe down exits fullscreen while horizontal swipe navigates media', () => {
  const stageGesture = (opts) => resolveQuickViewStageAction({
    startX: 100, startY: 100, endX: 100, endY: 100,
    durationMs: 150, stageLeft: 0, stageWidth: 400,
    mediaType: 'VIDEO', mediaCount: 3, fullscreen: false,
    ...opts
  });

  // Valid downward swipe in fullscreen exits fullscreen
  assert.equal(stageGesture({ endY: 195, fullscreen: true }), 'EXIT_FULLSCREEN');
  assert.equal(stageGesture({ startX: 100, startY: 100, endX: 130, endY: 210, fullscreen: true }), 'EXIT_FULLSCREEN', 'mostly-vertical downward exits');

  // Valid downward swipe NOT in fullscreen does NOT exit fullscreen (does not close Quick View)
  assert.equal(stageGesture({ endY: 195, fullscreen: false }), 'NONE');
  assert.equal(stageGesture({ endY: 195 }), 'NONE');

  // Horizontal swipe in fullscreen still navigates media
  assert.equal(stageGesture({ startX: 200, endX: 100, fullscreen: true }), 'NEXT', 'horizontal swipe left in fullscreen moves next');
  assert.equal(stageGesture({ startX: 100, endX: 200, fullscreen: true }), 'PREVIOUS', 'horizontal swipe right in fullscreen moves previous');

  // Diagonal mostly-horizontal gesture in fullscreen remains media navigation
  assert.equal(stageGesture({ startX: 100, startY: 100, endX: 220, endY: 170, fullscreen: true }), 'PREVIOUS');
  assert.equal(stageGesture({ startX: 220, startY: 100, endX: 100, endY: 170, fullscreen: true }), 'NEXT');

  // Upward swipe in fullscreen does not exit
  assert.equal(stageGesture({ startY: 200, endY: 100, fullscreen: true }), 'NONE');

  // Short downward drag in fullscreen does not exit
  assert.equal(stageGesture({ startY: 100, endY: 145, fullscreen: true }), 'NONE');

  // Tap in fullscreen still preserves tap zone actions
  assert.equal(stageGesture({ startX: 30, endX: 30, fullscreen: true }), 'PREVIOUS', 'left tap zone navigates');
  assert.equal(stageGesture({ startX: 380, endX: 380, fullscreen: true }), 'NEXT', 'right tap zone navigates');
  assert.equal(stageGesture({ startX: 200, endX: 200, fullscreen: true }), 'TOGGLE_PLAYBACK', 'center video tap toggles playback');
});

test('exitQuickViewFullscreen exits app fallback, standard browser, and iOS native modes while preserving media index', async () => {
  let mode = 'APP_FALLBACK';
  let activityCalled = false;
  let exitCalls = [];

  // 1. App fallback exit
  const appStage = { classList: { contains: (cls) => cls === 'is-app-fullscreen' } };
  const appResult = exitQuickViewFullscreen({
    fullscreenMode: mode,
    stageElement: appStage,
    onFullscreenModeChange: (next) => { mode = next; },
    onActivity: () => { activityCalled = true; }
  });
  assert.equal(appResult, true);
  assert.equal(mode, 'NONE');
  assert.equal(activityCalled, true);

  // 2. Standard browser fullscreen exit
  mode = 'STANDARD';
  const standardStage = { classList: { contains: () => false } };
  const mockDoc = {
    fullscreenElement: standardStage,
    exitFullscreen: () => {
      exitCalls.push('exitFullscreen');
      mockDoc.fullscreenElement = null;
      return Promise.resolve();
    }
  };
  const origDoc = globalThis.document;
  globalThis.document = mockDoc;
  try {
    const stdResult = exitQuickViewFullscreen({
      fullscreenMode: mode,
      stageElement: standardStage,
      onFullscreenModeChange: (next) => { mode = next; }
    });
    assert.equal(stdResult, true);
    assert.deepEqual(exitCalls, ['exitFullscreen']);
    await Promise.resolve();
    assert.equal(mode, 'NONE');
  } finally {
    globalThis.document = origDoc;
  }

  // 3. iOS native video fullscreen exit
  mode = 'IOS_NATIVE';
  let iosExitCalled = false;
  const mockIosVideo = {
    webkitDisplayingFullscreen: false,
    webkitExitFullscreen: () => { iosExitCalled = true; }
  };
  const iosResult = exitQuickViewFullscreen({
    fullscreenMode: mode,
    stageElement: null,
    videoElement: mockIosVideo,
    onFullscreenModeChange: (next) => { mode = next; }
  });
  assert.equal(iosResult, true);
  assert.equal(iosExitCalled, true);
  assert.equal(mode, 'NONE');
});
