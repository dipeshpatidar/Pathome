import { getMediaTagLabel } from './mediaTags.ts';

export type QuickViewMedia = {
  url: string;
  type: 'IMAGE' | 'VIDEO';
  tagLabel: string | null;
};

type QuickViewMediaSource = {
  taggedMedia?: Array<{
    mediaUrl?: string | null;
    mediaType?: string | null;
    roomTag?: unknown;
  }> | null;
  images?: Array<string | null | undefined> | null;
  videoUrl?: string | null;
  coverRoomTag?: unknown;
};

const VIDEO_URL_PATTERN = /\.(mp4|webm|mov)(?:[?#]|$)/i;

const mediaTypeFor = (mediaType: string | null | undefined, url: string): QuickViewMedia['type'] =>
  mediaType?.toUpperCase() === 'VIDEO_WALKTHROUGH'
    || mediaType?.toUpperCase() === 'VIDEO'
    || VIDEO_URL_PATTERN.test(url)
    ? 'VIDEO'
    : 'IMAGE';

/**
 * Retains the API's tagged-media order and includes valid legacy projections
 * that are not already represented there. Room tags are presentation metadata,
 * never a filter for whether a media item is included.
 */
export const getQuickViewMedia = (property: QuickViewMediaSource | null | undefined): QuickViewMedia[] => {
  if (!property) return [];

  const firstImageUrl = (property.images ?? []).find(
    (candidate): candidate is string => typeof candidate === 'string' && Boolean(candidate.trim())
  )?.trim();
  const tagged = (property.taggedMedia ?? []).flatMap((entry) => {
    const url = typeof entry?.mediaUrl === 'string' ? entry.mediaUrl.trim() : '';
    const tagLabel = getMediaTagLabel(entry.roomTag)
      ?? (url === firstImageUrl ? getMediaTagLabel(property.coverRoomTag) : null);
    return url ? [{
      url,
      type: mediaTypeFor(entry.mediaType, url),
      tagLabel
    } satisfies QuickViewMedia] : [];
  });
  const seenUrls = new Set(tagged.map((media) => media.url));
  const images = (property.images ?? []).flatMap((candidate) => {
    const url = typeof candidate === 'string' ? candidate.trim() : '';
    if (!url || seenUrls.has(url)) return [];
    seenUrls.add(url);
    return [{
      url,
      type: 'IMAGE' as const,
      tagLabel: url === firstImageUrl ? getMediaTagLabel(property.coverRoomTag) : null
    }];
  });

  const videoUrl = typeof property.videoUrl === 'string' ? property.videoUrl.trim() : '';
  const legacyPropertyVideo = videoUrl && !seenUrls.has(videoUrl)
    ? [{ url: videoUrl, type: 'VIDEO' as const, tagLabel: null }]
    : [];

  return [...tagged, ...images, ...legacyPropertyVideo];
};

export const moveQuickViewMediaIndex = (currentIndex: number, mediaCount: number, direction: -1 | 1): number => {
  if (mediaCount <= 0) return 0;
  return ((currentIndex + direction) % mediaCount + mediaCount) % mediaCount;
};

export const resolveQuickViewSwipeDirection = (
  startX: number,
  startY: number,
  endX: number,
  endY: number,
  threshold = 44
): -1 | 0 | 1 => {
  const deltaX = endX - startX;
  const deltaY = endY - startY;
  if (Math.abs(deltaX) < threshold || Math.abs(deltaX) <= Math.abs(deltaY)) return 0;
  return deltaX < 0 ? 1 : -1;
};

export const quickViewSwipeStartsOnControl = (target: { closest: (selector: string) => unknown } | null): boolean =>
  Boolean(target?.closest('button, a, input, select, textarea, [role="button"], [contenteditable="true"], [role="slider"], [data-quick-view-control]'));

export type QuickViewStageAction = 'PREVIOUS' | 'NEXT' | 'TOGGLE_PLAYBACK' | 'EXIT_FULLSCREEN' | 'NONE';
export type QuickViewStageGesture = {
  startX: number;
  startY: number;
  endX: number;
  endY: number;
  durationMs: number;
  stageLeft: number;
  stageWidth: number;
  mediaType: QuickViewMedia['type'];
  mediaCount: number;
  fullscreen?: boolean;
};

export const resolveQuickViewDownwardSwipe = (
  startX: number,
  startY: number,
  endX: number,
  endY: number,
  threshold = 80
): boolean => {
  const deltaX = endX - startX;
  const deltaY = endY - startY;
  return deltaY >= threshold && Math.abs(deltaY) > Math.abs(deltaX);
};

/** One release resolves to one action; a swipe never falls through to a tap zone. */
export const resolveQuickViewStageAction = (gesture: QuickViewStageGesture): QuickViewStageAction => {
  const swipe = resolveQuickViewSwipeDirection(gesture.startX, gesture.startY, gesture.endX, gesture.endY);
  if (swipe !== 0) return gesture.mediaCount > 1 ? (swipe === 1 ? 'NEXT' : 'PREVIOUS') : 'NONE';
  if (gesture.fullscreen && resolveQuickViewDownwardSwipe(gesture.startX, gesture.startY, gesture.endX, gesture.endY)) {
    return 'EXIT_FULLSCREEN';
  }
  const deltaX = gesture.endX - gesture.startX;
  const deltaY = gesture.endY - gesture.startY;
  if (Math.hypot(deltaX, deltaY) > 12 || gesture.durationMs > 600 || gesture.stageWidth <= 0) return 'NONE';
  const fraction = (gesture.endX - gesture.stageLeft) / gesture.stageWidth;
  if (fraction < 0 || fraction > 1) return 'NONE';
  if (fraction <= 0.28) return gesture.mediaCount > 1 ? 'PREVIOUS' : 'NONE';
  if (fraction >= 0.72) return gesture.mediaCount > 1 ? 'NEXT' : 'NONE';
  return gesture.mediaType === 'VIDEO' ? 'TOGGLE_PLAYBACK' : 'NONE';
};

export type QuickViewControlsState = { playing: boolean; visible: boolean; activityVersion: number };
export type QuickViewControlsEvent = 'PLAY' | 'PAUSE' | 'ACTIVITY' | 'TIMEOUT' | 'MEDIA_CHANGE';

export const nextQuickViewControlsState = (state: QuickViewControlsState, event: QuickViewControlsEvent): QuickViewControlsState => {
  switch (event) {
    case 'PLAY': return { playing: true, visible: true, activityVersion: state.activityVersion + 1 };
    case 'PAUSE':
    case 'MEDIA_CHANGE': return { playing: false, visible: true, activityVersion: state.activityVersion + 1 };
    case 'ACTIVITY': return state.playing ? { playing: true, visible: true, activityVersion: state.activityVersion + 1 } : state;
    case 'TIMEOUT': return state.playing ? { ...state, visible: false } : state;
  }
};

export type QuickViewFullscreenMode = 'NONE' | 'IOS_NATIVE' | 'STANDARD' | 'APP_FALLBACK';
export type QuickViewFullscreenStrategy = Exclude<QuickViewFullscreenMode, 'NONE'>;
export const quickViewFullscreenStrategy = (hasNativeVideo: boolean, hasStandardStage: boolean): QuickViewFullscreenStrategy =>
  hasNativeVideo ? 'IOS_NATIVE' : hasStandardStage ? 'STANDARD' : 'APP_FALLBACK';

export const lockQuickViewBodyScroll = (body: { style: { overflow: string } }): (() => void) => {
  const previousOverflow = body.style.overflow;
  body.style.overflow = 'hidden';
  return () => { body.style.overflow = previousOverflow; };
};

export const formatQuickViewVideoTime = (seconds: number): string => {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00';
  const minutes = Math.floor(seconds / 60);
  const remainder = Math.floor(seconds % 60);
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
};

export const isQuickViewFullscreenActive = (
  mode: QuickViewFullscreenMode,
  stageElement?: HTMLElement | null
): boolean =>
  mode !== 'NONE' || Boolean(typeof document !== 'undefined' && stageElement && document.fullscreenElement === stageElement);

export type ExitQuickViewFullscreenParams = {
  fullscreenMode: QuickViewFullscreenMode;
  stageElement: HTMLElement | null;
  videoElement?: (HTMLVideoElement & {
    webkitExitFullscreen?: () => void;
    webkitDisplayingFullscreen?: boolean;
  }) | null;
  onFullscreenModeChange: (mode: QuickViewFullscreenMode) => void;
  onActivity?: () => void;
};

export const exitQuickViewFullscreen = ({
  fullscreenMode,
  stageElement,
  videoElement,
  onFullscreenModeChange,
  onActivity
}: ExitQuickViewFullscreenParams): boolean => {
  onActivity?.();
  if (fullscreenMode === 'APP_FALLBACK' || stageElement?.classList.contains('is-app-fullscreen')) {
    onFullscreenModeChange('NONE');
    return true;
  }
  const iosVideo = videoElement;
  if (iosVideo?.webkitDisplayingFullscreen || fullscreenMode === 'IOS_NATIVE') {
    try {
      if (typeof iosVideo?.webkitExitFullscreen === 'function') {
        iosVideo.webkitExitFullscreen();
      }
      if (!iosVideo?.webkitDisplayingFullscreen) {
        onFullscreenModeChange('NONE');
      }
    } catch {
      if (!iosVideo?.webkitDisplayingFullscreen) {
        onFullscreenModeChange('NONE');
      }
    }
    return true;
  }
  if (typeof document !== 'undefined') {
    const isDocFullscreen = Boolean(stageElement && document.fullscreenElement === stageElement);
    if (isDocFullscreen || fullscreenMode === 'STANDARD') {
      if (typeof document.exitFullscreen === 'function' && document.fullscreenElement) {
        void document.exitFullscreen().then(() => {
          if (document.fullscreenElement !== stageElement) onFullscreenModeChange('NONE');
        }).catch(() => onFullscreenModeChange('STANDARD'));
      } else {
        onFullscreenModeChange('NONE');
      }
      return true;
    }
  }
  if (fullscreenMode !== 'NONE') {
    onFullscreenModeChange('NONE');
    return true;
  }
  return false;
};
