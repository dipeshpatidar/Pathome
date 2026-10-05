import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { build } from 'esbuild';

const playerSource = readFileSync(new URL('../components/QuickViewVideoPlayer.tsx', import.meta.url), 'utf8');
const dashboardSource = readFileSync(new URL('../components/TenantDashboard.tsx', import.meta.url), 'utf8');
const styles = readFileSync(new URL('../tenantV0.css', import.meta.url), 'utf8');
const originalWindow = globalThis.window;
const originalDocument = globalThis.document;
const originalWarn = console.warn;
afterEach(() => {
  globalThis.window = originalWindow;
  globalThis.document = originalDocument;
  console.warn = originalWarn;
});

const fakeReact = {
  createElement: (type, props, ...children) => ({ type, props: { ...props, children } }),
  useState: initial => [initial, () => {}],
  useRef: initial => ({ current: initial }),
  useEffect: () => {}
};
const bundle = await build({
  entryPoints: [new URL('../components/QuickViewVideoPlayer.tsx', import.meta.url).pathname],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
  external: ['react', 'react/jsx-runtime', 'lucide-react']
});
const module = { exports: {} };
const mockRequire = name => {
  if (name === 'react') return { ...fakeReact, default: fakeReact };
  if (name === 'react/jsx-runtime') return {
    jsx: (type, props) => ({ type, props }),
    jsxs: (type, props) => ({ type, props })
  };
  if (name === 'lucide-react') return { Maximize2: 'icon', Pause: 'icon', Play: 'icon', Volume2: 'icon', VolumeX: 'icon' };
  throw new Error(`Unexpected import: ${name}`);
};
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(mockRequire, module, module.exports);
const { QuickViewVideoPlayer } = module.exports;

const findFullscreenButton = tree => {
  if (!tree || typeof tree !== 'object') return null;
  if (tree.type === 'button' && /video fullscreen/.test(tree.props?.['aria-label'] ?? '')) return tree;
  for (const child of [tree.props?.children].flat(Infinity)) {
    const found = findFullscreenButton(child);
    if (found) return found;
  }
  return null;
};

const runClick = ({ native, standard, fullscreenMode = 'NONE' } = {}) => {
  const calls = [];
  const timers = new Map();
  let nextTimer = 1;
  let mode = fullscreenMode;
  const stage = { ...standard };
  const video = { paused: true, duration: 15, currentTime: 0, ...native };
  const document = { fullscreenElement: null, exitFullscreen: () => { document.fullscreenElement = null; return Promise.resolve(); } };
  globalThis.window = {
    setTimeout: callback => { const id = nextTimer++; timers.set(id, callback); return id; },
    clearTimeout: id => timers.delete(id)
  };
  globalThis.document = document;
  console.warn = () => {};
    const tree = QuickViewVideoPlayer({
      src: '/home.mp4', label: 'Home video', videoRef: { current: video }, stageRef: { current: stage },
      playing: false, playbackError: null, onPlaybackError: () => {}, onTogglePlayback: () => {},
      onPlaybackChange: () => {}, onActivity: () => {}, onError: () => {}, fullscreenMode,
      onFullscreenModeChange: update => { mode = typeof update === 'function' ? update(mode) : update; }
    });
    const button = findFullscreenButton(tree);
    assert.ok(button, 'fullscreen button is rendered');
    button.props.onClick({ stopPropagation: () => calls.push('stop-propagation') });
    return {
      calls, timers, document, stage, video, button, get mode() { return mode; },
      flushTimers: () => { for (const [id, callback] of [...timers]) { timers.delete(id); callback(); } }
    };
};

test('native video fullscreen is invoked synchronously before standard fullscreen', () => {
  const calls = [];
  const result = runClick({
    native: { webkitEnterFullscreen: () => calls.push('native') },
    standard: { requestFullscreen: () => { calls.push('standard'); return Promise.resolve(); } }
  });
  assert.deepEqual(calls, ['native']);
  assert.deepEqual(result.calls, ['stop-propagation']);
  result.button.props.onPointerDown({ stopPropagation: () => result.calls.push('pointer-down-stopped') });
  result.button.props.onPointerUp({ stopPropagation: () => result.calls.push('pointer-up-stopped') });
  assert.deepEqual(result.calls, ['stop-propagation', 'pointer-down-stopped', 'pointer-up-stopped']);
});

test('native fullscreen can enter while the video is paused', () => {
  const result = runClick({ native: { webkitEnterFullscreen() { this.webkitDisplayingFullscreen = true; } } });
  assert.equal(result.video.paused, true);
  assert.equal(result.mode, 'IOS_NATIVE');
  assert.equal(result.timers.size, 0);
});

test('native method absent uses standard fullscreen directly from the click', async () => {
  const calls = [];
  const result = runClick({ standard: { requestFullscreen: () => { calls.push('standard'); return Promise.resolve(); } } });
  assert.deepEqual(calls, ['standard']);
  result.document.fullscreenElement = result.stage;
  await Promise.resolve();
  assert.equal(result.mode, 'STANDARD');
});

test('native throw tries standard in the same click; no API uses app fullscreen', () => {
  const calls = [];
  runClick({
    native: { webkitEnterFullscreen: () => { calls.push('native'); throw new Error('blocked'); } },
    standard: { requestFullscreen: () => { calls.push('standard'); return Promise.resolve(); } }
  });
  assert.deepEqual(calls, ['native', 'standard']);
  const fallback = runClick();
  assert.equal(fallback.mode, 'APP_FALLBACK');
});

test('native no-op and rejected standard request both reach app fullscreen', async () => {
  const nativeNoOp = runClick({ native: { webkitEnterFullscreen: () => {} } });
  assert.equal(nativeNoOp.mode, 'NONE');
  nativeNoOp.flushTimers();
  assert.equal(nativeNoOp.mode, 'APP_FALLBACK');

  const standardFailure = runClick({ standard: { requestFullscreen: () => Promise.reject(new Error('denied')) } });
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(standardFailure.mode, 'APP_FALLBACK');

  const pendingStandard = runClick({ standard: { requestFullscreen: () => new Promise(() => {}) } });
  pendingStandard.flushTimers();
  assert.equal(pendingStandard.mode, 'APP_FALLBACK', 'a stalled fullscreen promise cannot leave the button dead');
});

test('app fullscreen button exits without changing media or playback', () => {
  const result = runClick({ fullscreenMode: 'APP_FALLBACK' });
  assert.equal(result.mode, 'NONE');
  assert.match(dashboardSource, /setActiveMediaIndex\(index => moveQuickViewMediaIndex/);
  const fullscreenHandler = playerSource.slice(playerSource.indexOf('const handleFullscreenClick ='), playerSource.indexOf('const updateDuration ='));
  assert.doesNotMatch(fullscreenHandler, /setActiveMediaIndex|onTogglePlayback\(/);
  assert.match(dashboardSource, /if \(fullscreenMode !== 'APP_FALLBACK'\) return;\s*return lockQuickViewBodyScroll\(document\.body\)/);
  assert.match(styles, /\.tenant-v0-quick-media\.is-app-fullscreen \{[^}]*position: fixed; inset: 0;[^}]*height: 100dvh/);
});

test('TenantDashboard wires downward swipe to exit fullscreen without closing Quick View or changing media index', () => {
  // Verifies TenantDashboard passes isFullscreen into resolveQuickViewStageAction
  assert.match(dashboardSource, /const isFullscreen = isQuickViewFullscreenActive\(fullscreenMode, mediaStageRef\.current\);/);
  assert.match(dashboardSource, /fullscreen: isFullscreen/);

  // Verifies EXIT_FULLSCREEN calls exitQuickViewFullscreen
  assert.match(dashboardSource, /else if \(action === 'EXIT_FULLSCREEN'\) \{\s*exitQuickViewFullscreen\(\{/);

  // Verifies EXIT_FULLSCREEN does not call changeMedia or onClose
  const exitFullscreenBranch = dashboardSource.slice(
    dashboardSource.indexOf("else if (action === 'EXIT_FULLSCREEN')"),
    dashboardSource.indexOf("const formatMoney =")
  );
  assert.doesNotMatch(exitFullscreenBranch, /changeMedia|onClose|setActiveMediaIndex/);

  // Verifies interactive controls exclusion helper is used at gesture start
  assert.match(dashboardSource, /eligible: !quickViewSwipeStartsOnControl\(target\)/);
});
