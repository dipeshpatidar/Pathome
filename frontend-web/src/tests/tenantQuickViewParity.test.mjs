import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { build } from 'esbuild';

const require = createRequire(import.meta.url);
const dashboard = readFileSync(new URL('../components/TenantDashboard.tsx', import.meta.url), 'utf8');
const home = readFileSync(new URL('../components/Home.tsx', import.meta.url), 'utf8');
const rail = readFileSync(new URL('../components/TenantNavigationRail.tsx', import.meta.url), 'utf8');
const lessorWorkspace = readFileSync(new URL('../components/LessorWorkspace.tsx', import.meta.url), 'utf8');
const styles = readFileSync(new URL('../tenantV0.css', import.meta.url), 'utf8');
const player = readFileSync(new URL('../components/QuickViewVideoPlayer.tsx', import.meta.url), 'utf8');
const quickViewStart = dashboard.indexOf('const TenantPropertyQuickView:');
const quickViewEnd = dashboard.indexOf('\ntype FavoriteState', quickViewStart);
const quickView = dashboard.slice(quickViewStart, quickViewEnd);

const source = `
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { TenantNavigationRail } from '../components/TenantNavigationRail';
import { TenantMobileDock } from '../components/TenantMobileDock';
const noop = () => {};
export function renderSavedNavigation(savedCount) {
  return renderToStaticMarkup(<TenantNavigationRail activeItem="home" onNavigate={noop}
    onOpenLessor={noop} onOpenAccount={noop} hasLessorCapability={false}
    savedCount={savedCount} accountName="Tenant" />);
}
export function renderMobileDock(savedCount) {
  return renderToStaticMarkup(<TenantMobileDock activeItem="lessor" savedCount={savedCount}
    hasLessorCapability={true} onNavigate={noop} onOpenListings={noop} />);
}
`;
const output = await build({
  stdin: { contents: source, resolveDir: new URL('.', import.meta.url).pathname, sourcefile: 'saved-navigation-harness.tsx', loader: 'tsx' },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
  external: ['react', 'react-dom/server', 'lucide-react']
});
const bundled = { exports: {} };
new Function('require', 'module', 'exports', output.outputFiles[0].text)(require, bundled, bundled.exports);
const { renderSavedNavigation, renderMobileDock } = bundled.exports;

test('Quick View adapts the PR split presentation without replacing real media or actions', () => {
  assert.ok(quickViewStart >= 0 && quickViewEnd > quickViewStart);
  assert.match(quickView, /getQuickViewMedia\(property\)/);
  assert.match(quickView, /tenant-v0-quick-media/);
  assert.match(quickView, /activeMedia\?\.tagLabel &&/);
  assert.match(quickView, /tenant-v0-quick-media-tag-text/);
  assert.match(quickView, /<QuickViewVideoPlayer key=/);
  assert.match(player, /<video ref=\{videoRef\} src=\{src\} playsInline preload="metadata"/);
  assert.doesNotMatch(player, /<video[^>]*\bcontrols\b/);
  assert.match(quickView, /<img key=\{`\$\{activeMedia\.url\}-\$\{activeMediaIndex\}`\}[^>]+alt=\{/);
  assert.match(quickView, /onError=\{\(\) => setFailedMedia/);
  assert.match(quickView, /onPointerDown=\{handleMediaPointerDown\} onPointerUp=\{handleMediaPointerUp\}/);
  assert.match(quickView, /resolveQuickViewStageAction\(\{/);
  assert.match(quickView, /action === 'PREVIOUS' \|\| action === 'NEXT'/);
  assert.match(quickView, /action === 'TOGGLE_PLAYBACK'\) toggleActiveVideo\(\)/);
  assert.match(quickView, /quickViewSwipeStartsOnControl\(target\)/);
  assert.doesNotMatch(quickView, /onTouchStart=|onTouchEnd=|onClick=\{revealControls\}/);
  assert.match(quickView, /event\.key === 'ArrowLeft' \|\| event\.key === 'ArrowRight'/);
  assert.match(quickView, /target\?\.closest\('input, select, textarea, \[contenteditable="true"\], \[role="slider"\], \[role="textbox"\]'\)/);
  assert.match(quickView, /document\.fullscreenElement === mediaStageRef\.current \|\| iosVideo\?\.webkitDisplayingFullscreen/);
  assert.ok(quickView.indexOf('const media = useMemo') < quickView.indexOf('}, [media.length, changeMedia, revealControls, toggleActiveVideo]);'), 'media is initialized before the keyboard effect reads its length');
  assert.match(quickView, /activeVideoRef\.current\?\.pause\(\);[\s\S]*?setActiveMediaIndex\(index => moveQuickViewMediaIndex/);
  assert.match(quickView, /activeVideoRef\.current === video\) setVideoControls/);
  assert.doesNotMatch(quickView, /Previous property media|Next property media|tenant-v0-quick-media-nav/);
  assert.match(quickView, /media\.length > 0 && <span className="tenant-v0-quick-media-count"/);
  assert.match(quickView, /\{activeMediaIndex \+ 1\} \/ \{media\.length\}/);
  assert.match(quickView, /tenant-v0-quick-facts/);
  assert.match(quickView, /tenant-v0-quick-panel/);
  assert.match(quickView, /\{ label: 'Area', value: Number\.isFinite\(property\.totalAreaSqFt\)/);
  assert.match(quickView, /\{ label: 'Bathrooms', value: property\.bathroomCount && property\.bathroomCount > 0/);
  assert.match(quickView, /facts\.map\(fact => <span key=\{fact\.label\}><strong>\{fact\.value\}<\/strong><small>\{fact\.label\}<\/small><\/span>\)/);
  assert.match(quickView, /tenant-v0-quick-status/);
  assert.match(quickView, /tenant-v0-quick-footer/);
  assert.match(quickView, /tenantVisitCtaLabel\(visitRequestStatus\(property\.id\)\)/);
  assert.match(quickView, /onToggleFavorite\(property\)/);
  assert.match(quickView, /onViewProperty\(property\)/);
  assert.doesNotMatch(quickView, /media thumbnails|DETAIL_THUMBNAIL/);
  assert.doesNotMatch(quickView, /Sample listing|Verified by Pathome/);
  assert.match(styles, /\.tenant-v0-quick-modal \{[^}]*grid-template-columns: minmax\(0,1\.08fr\) minmax\(390px,\.92fr\); width: min\(100%,1060px\)/);
  assert.match(styles, /\.tenant-v0-quick-modal\.is-pending \{ display: block/);
  assert.match(styles, /\.tenant-v0-quick-media > img \{[^}]*object-fit: cover/);
  assert.match(styles, /\.tenant-v0-quick-player > video \{[^}]*object-fit: contain; pointer-events: none/);
  assert.match(styles, /\.tenant-v0-quick-media-tag \{[^}]*top: 18px/);
  assert.match(styles, /\.tenant-v0-quick-facts \{[^}]*grid-template-columns: repeat\(2,minmax\(0,1fr\)\)/);
  assert.doesNotMatch(styles, /tenant-v0-quick-media-nav/);
  assert.match(styles, /\.tenant-v0-quick-media-count \{/);
  assert.match(styles, /@media \(max-width: 800px\) \{[\s\S]*?\.tenant-v0-quick-modal \{ display: flex; flex-direction: column/);
});

test('Quick View Space controls the focused desktop video stage without stealing control keys', () => {
  assert.match(quickView, /\(event\.key === ' ' \|\| event\.code === 'Space'\) && !event\.repeat/);
  assert.match(quickView, /document\.activeElement === mediaStageRef\.current && activeVideoRef\.current/);
  assert.match(quickView, /event\.preventDefault\(\);\s*revealControls\(\);\s*toggleActiveVideo\(\);/);
  assert.match(quickView, /activeMedia\?\.type === 'VIDEO' && window\.matchMedia\('\(pointer: fine\)'\)\.matches/);
  assert.match(quickView, /mediaStageRef\.current\?\.focus\(\{ preventScroll: true \}\)/);
  assert.match(quickView, /event\.pointerType === 'mouse' && !quickViewSwipeStartsOnControl\(target\)/);
  assert.match(quickView, /event\.currentTarget\.focus\(\{ preventScroll: true \}\)/);
});

test('Quick View uses one image/video overlay geometry and controlled playback with swipe navigation', () => {
  const mobileStyles = styles.slice(styles.indexOf('@media (max-width: 600px)'));
  assert.match(quickView, /activeMedia\.type === 'VIDEO'[\s\S]*?<QuickViewVideoPlayer[\s\S]*?: <img/);
  assert.doesNotMatch(quickView, /10.?second|rewind|fast.forward|skip control|ChevronLeft|ChevronRight/i);
  assert.match(quickView, /changeMedia\(action === 'NEXT' \? 1 : -1, media\.length\)/);
  assert.match(mobileStyles, /\.tenant-v0-quick-media-tag \{ top: 8px; left: 9px; max-width: calc\(50% - 50px\)/);
  assert.match(styles, /\.tenant-v0-quick-media-count \{[^}]*left: 50%;[^}]*translateX\(-50%\)/);
  assert.match(mobileStyles, /\.tenant-v0-quick-close \{ top: 5px; right: 5px; width: 44px; height: 44px/);
  assert.match(styles, /\.tenant-v0-quick-play \{ display: none; \}/);
  assert.match(styles, /\.tenant-v0-quick-toolbar \.tenant-v0-quick-mobile-play \{ display: grid; \}/);
  assert.doesNotMatch(mobileStyles, /\.tenant-v0-quick-media\.is-video \.tenant-v0-quick-(media-tag|media-count|close)/);
  assert.match(styles, /\.tenant-v0-quick-media \{ position: relative;[^}]*overflow: hidden/);
  assert.match(quickView, /1800/);
  assert.match(quickView, /scheduledVersion = videoControls\.activityVersion/);
  assert.match(quickView, /\}, \[videoControls\.playing, videoControls\.activityVersion\]\);/);
  assert.match(quickView, /onPointerMove=\{event => \{ if \(event\.pointerType === 'mouse'/);
  assert.match(quickView, /onFocusCapture=\{revealControls\}/);
  assert.match(styles, /\.tenant-v0-quick-player-controls \{[^}]*pointer-events: none/);
  assert.match(styles, /\.tenant-v0-quick-seek-row input \{[^}]*touch-action: none; pointer-events: auto/);
  assert.match(styles, /\.tenant-v0-quick-toolbar button \{[^}]*pointer-events: auto/);
  assert.match(styles, /\.tenant-v0-quick-media\.is-controls-hidden \.tenant-v0-quick-media-tag,[\s\S]*?\.tenant-v0-quick-player-controls \{ opacity: 0; pointer-events: none/);
  assert.match(player, /onPlay=\{event => onPlaybackChange\(true, event\.currentTarget\)\}/);
  assert.match(player, /return \(\) => \{ video\?\.pause\(\); \}/);
  assert.match(quickView, /onError=\{\(\) => \{[\s\S]*?nextQuickViewControlsState\(previous, 'PAUSE'\)/);
  assert.match(player, /onTimeUpdate=\{event => setCurrentTime\(event\.currentTarget\.currentTime\)\}/);
  assert.doesNotMatch(player, /onTimeUpdate=\{[^}]*onActivity/);
  assert.match(player, /const handleFullscreenClick = \(event: React\.MouseEvent<HTMLButtonElement>\) => \{/);
  assert.match(player, /event\.stopPropagation\(\);[\s\S]*?if \(strategy === 'IOS_NATIVE' && typeof iosVideo\.webkitEnterFullscreen === 'function'\) \{\s*try \{\s*nativeEnteredRef\.current = false;\s*iosVideo\.webkitEnterFullscreen\(\)/);
  assert.ok(player.indexOf('iosVideo.webkitEnterFullscreen();') < player.indexOf('stage.requestFullscreen();'), 'native video is invoked before stage fullscreen');
  assert.doesNotMatch(player, /pointer: coarse|pointer: fine|userAgent|innerWidth/);
  assert.match(player, /request\.then\([\s\S]*?enterAppFullscreen\('STANDARD_FULLSCREEN_FAILED'\)/);
  assert.match(player, /enterAppFullscreen\('NATIVE_IOS_FAILED'\)/);
  assert.match(player, /onPointerDown=\{event => event\.stopPropagation\(\)\} onPointerUp=\{event => event\.stopPropagation\(\)\} onClick=\{handleFullscreenClick\}/);
  assert.match(player, /addEventListener\('webkitbeginfullscreen'/);
  assert.match(player, /addEventListener\('fullscreenchange'/);
  assert.match(player, /removeEventListener\('fullscreenchange'/);
  assert.match(player, /addEventListener\('webkitendfullscreen'/);
  assert.match(player, /removeEventListener\('webkitbeginfullscreen'/);
  assert.match(quickView, /fullscreenMode=\{fullscreenMode\} onFullscreenModeChange=\{setFullscreenMode\}/);
  assert.match(quickView, /fullscreenMode === 'APP_FALLBACK' \? 'is-app-fullscreen' : ''/);
  assert.match(quickView, /if \(fullscreenMode !== 'APP_FALLBACK'\) return;\s*return lockQuickViewBodyScroll\(document\.body\)/);
  assert.match(styles, /\.tenant-v0-quick-media\.is-app-fullscreen \{[^}]*position: fixed; inset: 0; z-index: 1000;[^}]*height: 100dvh/);
  assert.match(styles, /\.tenant-v0-quick-media\.is-app-fullscreen \.tenant-v0-quick-player-controls \{[^}]*env\(safe-area-inset-bottom\)/);
  for (const control of ['Play video', 'Pause video', 'Mute video', 'Seek video', 'Enter video fullscreen']) {
    assert.ok(player.includes(control));
  }
});

test('Home owns one saved count and shares it across tenant and Listings navigation', () => {
  assert.match(home, /loadAllSavedPropertyIds\(/);
  assert.match(home, /authoritativeSavedCount = tenantFavoriteSession[\s\S]*?tenantFavoritesSnapshot\.propertyIds\.size/);
  assert.match(home, /<TenantDashboard[\s\S]*?savedCount=\{authoritativeSavedCount\}/);
  assert.match(home, /<LessorWorkspace[\s\S]*?savedCount=\{authoritativeSavedCount\}/);
  assert.match(dashboard, /tenantMobileDockBadges\(searchFilters, undefined, savedCount\)/);
  assert.match(dashboard, /savedCount=\{savedCount\}/);
  assert.match(dashboard, /savedCount=\{mobileDockBadges\.savedCount\}/);
  assert.match(lessorWorkspace, /savedCount=\{savedCount\}/);
  assert.match(lessorWorkspace, /<TenantMobileDock activeItem="lessor" savedCount=\{savedCount\}/);
  assert.match(home, /\[favoriteCountReload, role, tenantFavoriteSession\?\.key, user\?\.id\]/);
  assert.match(rail, /aria-label=\{itemCount \? `\$\{label\}, \$\{itemCount\} \$\{itemCount === 1 \? 'property' : 'properties'\}` : label\}/);

  const zero = renderSavedNavigation(null);
  assert.match(zero, /aria-label="Saved homes"/);
  assert.doesNotMatch(zero, /tenant-shell-rail-count/);

  for (const count of [1, 4]) {
    const markup = renderSavedNavigation(count);
    assert.match(markup, new RegExp(`aria-label="Saved homes, ${count} ${count === 1 ? 'property' : 'properties'}"`));
    assert.match(markup, new RegExp(`<small class="tenant-shell-rail-count" aria-hidden="true">${count}<\\/small>`));
  }

  const mobileZero = renderMobileDock(null);
  assert.match(mobileZero, /<span>Saved<\/span>/);
  assert.doesNotMatch(mobileZero, /<i aria-hidden="true">/);
  const mobilePositive = renderMobileDock(3);
  assert.match(mobilePositive, /aria-label="Saved homes, 3 properties"/);
  assert.match(mobilePositive, /<i aria-hidden="true">3<\/i>/);
  assert.match(mobilePositive, /<span>Listings<\/span>/);
});

test('Explore, Saved, and Visits use a shared desktop row geometry', () => {
  assert.equal((rail.match(/tenant-shell-primary-link/g) || []).length, 1);
  assert.match(rail, /tenant-shell-rail-link tenant-shell-primary-link/);
  assert.match(styles, /\.tenant-shell-primary-link \{ display: grid; grid-template-columns: 16px minmax\(0,1fr\) auto; align-items: center; column-gap: 12px;/);
  assert.match(styles, /\.tenant-shell-primary-link > \.tenant-shell-rail-icon \{ display: grid; place-items: center; width: 16px; height: 16px/);
  assert.match(styles, /\.tenant-shell-primary-link > \.tenant-shell-rail-icon \{ width: 16px; height: 16px; margin: 0; \}/);
  assert.match(styles, /\.tenant-shell-rail-count \{[^}]*justify-self: end/);
  assert.doesNotMatch(styles, /\.tenant-shell-primary-link\.is-active|tenant-shell-primary-link[^}]*margin-left/);
  const rows = renderSavedNavigation(3).match(/<button[^>]*tenant-shell-primary-link[^>]*>[\s\S]*?<\/button>/g) ?? [];
  assert.equal(rows.length, 3);
  for (const row of rows) {
    assert.match(row, /<span class="tenant-shell-rail-icon"><svg/);
    assert.match(row, /<span class="tenant-shell-rail-label hidden lg:block">/);
    assert.ok(row.indexOf('tenant-shell-rail-icon') < row.indexOf('tenant-shell-rail-label'));
  }
  assert.match(rows[1], /tenant-shell-rail-label hidden lg:block">Saved homes<\/span><small class="tenant-shell-rail-count"/);
  assert.ok(rows[0].includes('is-active'));
  assert.ok(!rows[1].includes('is-active') && !rows[2].includes('is-active'));
});

test('successful favorite changes from dashboard and property detail notify the shared owner', () => {
  const propertyDetail = readFileSync(new URL('../components/PublicPropertyDetail.tsx', import.meta.url), 'utf8');
  assert.match(dashboard, /notifyTenantFavoriteChanged\(\{ identityKey: session\.key, propertyId: property\.id, saved: !wasSaved \}\)/);
  assert.match(propertyDetail, /notifyTenantFavoriteChanged\(\{ identityKey: session\.key, propertyId, saved: !previousSaved \}\)/);
  assert.match(home, /applyTenantFavoriteChanged\(snapshot, session\.key, detail\.propertyId, detail\.saved\)/);
  assert.match(home, /pendingChanges\.set\(detail\.propertyId, detail\.saved\)/);
});
