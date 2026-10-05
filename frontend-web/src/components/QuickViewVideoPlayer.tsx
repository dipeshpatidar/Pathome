import React, { useEffect, useRef, useState } from 'react';
import { Maximize2, Pause, Play, Volume2, VolumeX } from 'lucide-react';
import { formatQuickViewVideoTime, quickViewFullscreenStrategy, type QuickViewFullscreenMode } from '../utils/quickViewMedia';

type Props = {
  src: string;
  label: string;
  videoRef: React.RefObject<HTMLVideoElement>;
  stageRef: React.RefObject<HTMLDivElement>;
  playing: boolean;
  playbackError: string | null;
  onPlaybackError: (message: string | null) => void;
  onTogglePlayback: () => void;
  onPlaybackChange: (playing: boolean, video: HTMLVideoElement) => void;
  onActivity: () => void;
  onError: () => void;
  fullscreenMode: QuickViewFullscreenMode;
  onFullscreenModeChange: React.Dispatch<React.SetStateAction<QuickViewFullscreenMode>>;
};

/** Playback controls for the shared Quick View stage; media traversal stays with the stage. */
export const QuickViewVideoPlayer: React.FC<Props> = ({
  src, label, videoRef, stageRef, playing, playbackError, onPlaybackError, onTogglePlayback,
  onPlaybackChange, onActivity, onError, fullscreenMode, onFullscreenModeChange
}) => {
  const [muted, setMuted] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const nativeEntryTimerRef = useRef<number | null>(null);
  const standardEntryTimerRef = useRef<number | null>(null);
  const standardAttemptRef = useRef(0);
  const nativeEnteredRef = useRef(false);
  const fullscreenButtonRef = useRef<HTMLButtonElement>(null);

  const clearNativeEntryTimer = () => {
    if (nativeEntryTimerRef.current !== null) window.clearTimeout(nativeEntryTimerRef.current);
    nativeEntryTimerRef.current = null;
  };
  const clearStandardEntryTimer = () => {
    if (standardEntryTimerRef.current !== null) window.clearTimeout(standardEntryTimerRef.current);
    standardEntryTimerRef.current = null;
  };

  useEffect(() => {
    const video = videoRef.current;
    return () => { video?.pause(); };
  }, [src, videoRef]);

  useEffect(() => {
    if (fullscreenMode === 'APP_FALLBACK') fullscreenButtonRef.current?.focus({ preventScroll: true });
  }, [fullscreenMode]);

  useEffect(() => {
    const stage = stageRef.current;
    const video = videoRef.current;
    if (!stage || !video) return;
    const syncStandard = () => {
      clearStandardEntryTimer();
      if (document.fullscreenElement !== stage) standardAttemptRef.current += 1;
      onFullscreenModeChange(previous =>
        document.fullscreenElement === stage ? 'STANDARD' : previous === 'STANDARD' ? 'NONE' : previous);
    };
    const enteredVideo = () => {
      nativeEnteredRef.current = true;
      clearNativeEntryTimer();
      onFullscreenModeChange('IOS_NATIVE');
    };
    const exitedVideo = () => {
      nativeEnteredRef.current = false;
      clearNativeEntryTimer();
      onFullscreenModeChange('NONE');
    };
    document.addEventListener('fullscreenchange', syncStandard);
    video.addEventListener('webkitbeginfullscreen', enteredVideo);
    video.addEventListener('webkitendfullscreen', exitedVideo);
    return () => {
      clearNativeEntryTimer();
      clearStandardEntryTimer();
      standardAttemptRef.current += 1;
      nativeEnteredRef.current = false;
      document.removeEventListener('fullscreenchange', syncStandard);
      video.removeEventListener('webkitbeginfullscreen', enteredVideo);
      video.removeEventListener('webkitendfullscreen', exitedVideo);
      onFullscreenModeChange(previous => previous === 'IOS_NATIVE' ? 'NONE' : previous);
    };
  }, [stageRef, videoRef, onFullscreenModeChange]);

  const toggleMute = () => {
    const video = videoRef.current;
    if (!video) return;
    onActivity();
    video.muted = !video.muted;
    setMuted(video.muted);
  };

  const enterAppFullscreen = (reason: 'NATIVE_IOS_FAILED' | 'STANDARD_FULLSCREEN_FAILED' | 'APP_FULLSCREEN_USED') => {
    console.warn('Quick View fullscreen fallback', { reason });
    onFullscreenModeChange('APP_FALLBACK');
  };

  const handleFullscreenClick = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    const stage = stageRef.current;
    const video = videoRef.current;
    if (!stage || !video) return;
    const iosVideo = video as HTMLVideoElement & {
      webkitEnterFullscreen?: () => void;
      webkitExitFullscreen?: () => void;
      webkitDisplayingFullscreen?: boolean;
    };
    if (fullscreenMode === 'APP_FALLBACK') {
      onFullscreenModeChange('NONE');
      onActivity();
      return;
    }
    if (iosVideo.webkitDisplayingFullscreen || fullscreenMode === 'IOS_NATIVE') {
      try {
        if (typeof iosVideo.webkitExitFullscreen === 'function') iosVideo.webkitExitFullscreen();
        if (!iosVideo.webkitDisplayingFullscreen) onFullscreenModeChange('NONE');
      } catch {
        if (!iosVideo.webkitDisplayingFullscreen) onFullscreenModeChange('NONE');
      }
      return;
    }
    if (document.fullscreenElement === stage || fullscreenMode === 'STANDARD') {
      if (typeof document.exitFullscreen === 'function') {
        void document.exitFullscreen().then(() => {
          if (document.fullscreenElement !== stage) onFullscreenModeChange('NONE');
        }).catch(() => onFullscreenModeChange('STANDARD'));
      }
      return;
    }

    // Keep the native call directly in this click handler, before any promise or state update.
    if (nativeEntryTimerRef.current !== null) return;
    const strategy = quickViewFullscreenStrategy(
      typeof iosVideo.webkitEnterFullscreen === 'function', typeof stage.requestFullscreen === 'function');
    if (strategy === 'IOS_NATIVE' && typeof iosVideo.webkitEnterFullscreen === 'function') {
      try {
        nativeEnteredRef.current = false;
        iosVideo.webkitEnterFullscreen();
        onActivity();
        onPlaybackError(null);
        if (nativeEnteredRef.current || iosVideo.webkitDisplayingFullscreen) onFullscreenModeChange('IOS_NATIVE');
        else nativeEntryTimerRef.current = window.setTimeout(() => {
          nativeEntryTimerRef.current = null;
          if (videoRef.current !== video) return;
          if (nativeEnteredRef.current || iosVideo.webkitDisplayingFullscreen) onFullscreenModeChange('IOS_NATIVE');
          else enterAppFullscreen('NATIVE_IOS_FAILED');
        }, 700);
        return;
      } catch {
        console.warn('Quick View native video fullscreen failed', { reason: 'NATIVE_IOS_FAILED' });
      }
    }

    if (typeof stage.requestFullscreen === 'function') {
      try {
        const request = stage.requestFullscreen();
        onActivity();
        onPlaybackError(null);
        const attempt = ++standardAttemptRef.current;
        standardEntryTimerRef.current = window.setTimeout(() => {
          standardEntryTimerRef.current = null;
          if (standardAttemptRef.current !== attempt || videoRef.current !== video) return;
          if (document.fullscreenElement !== stage) {
            standardAttemptRef.current += 1;
            enterAppFullscreen('STANDARD_FULLSCREEN_FAILED');
          }
        }, 1000);
        void request.then(() => {
          if (standardAttemptRef.current !== attempt) return;
          clearStandardEntryTimer();
          if (document.fullscreenElement === stage) onFullscreenModeChange('STANDARD');
          else enterAppFullscreen('STANDARD_FULLSCREEN_FAILED');
        }).catch(() => {
          if (standardAttemptRef.current !== attempt) return;
          clearStandardEntryTimer();
          if (document.fullscreenElement === stage) onFullscreenModeChange('STANDARD');
          else enterAppFullscreen('STANDARD_FULLSCREEN_FAILED');
        });
        return;
      } catch {
        enterAppFullscreen('STANDARD_FULLSCREEN_FAILED');
        return;
      }
    }
    enterAppFullscreen('APP_FULLSCREEN_USED');
  };

  const updateDuration = (video: HTMLVideoElement) => {
    setDuration(Number.isFinite(video.duration) && video.duration > 0 ? video.duration : 0);
  };

  return <div className="tenant-v0-quick-player" role="group" aria-label="Property video player">
    <video ref={videoRef} src={src} playsInline preload="metadata" aria-label={label}
      onPlay={event => onPlaybackChange(true, event.currentTarget)}
      onPause={event => onPlaybackChange(false, event.currentTarget)}
      onEnded={event => onPlaybackChange(false, event.currentTarget)}
      onTimeUpdate={event => setCurrentTime(event.currentTarget.currentTime)}
      onLoadedMetadata={event => updateDuration(event.currentTarget)}
      onDurationChange={event => updateDuration(event.currentTarget)} onError={onError} />
    {!playing && <button type="button" className="tenant-v0-quick-play" aria-label="Play video" onClick={() => { onActivity(); onTogglePlayback(); }}>
      <Play size={30} fill="currentColor" aria-hidden="true" />
    </button>}
    <div className="tenant-v0-quick-player-controls" data-quick-view-control="true">
      <div className="tenant-v0-quick-seek-row">
        <span>{formatQuickViewVideoTime(currentTime)}</span>
        <input type="range" aria-label="Seek video" min={0} max={duration || 0} step={0.1}
          value={Math.min(currentTime, duration || 0)} disabled={!duration}
          onChange={event => {
            const time = Number(event.currentTarget.value);
            if (videoRef.current) videoRef.current.currentTime = time;
            setCurrentTime(time);
            onActivity();
          }} />
        <span>{formatQuickViewVideoTime(duration)}</span>
      </div>
      <div className="tenant-v0-quick-toolbar">
        {playing
          ? <button type="button" aria-label="Pause video" onClick={() => { onActivity(); onTogglePlayback(); }}><Pause size={18} fill="currentColor" aria-hidden="true" /></button>
          : <button type="button" className="tenant-v0-quick-mobile-play" aria-label="Play video" onClick={() => { onActivity(); onTogglePlayback(); }}><Play size={18} fill="currentColor" aria-hidden="true" /></button>}
        <button type="button" aria-label={muted ? 'Unmute video' : 'Mute video'} onClick={toggleMute}>
          {muted ? <VolumeX size={19} aria-hidden="true" /> : <Volume2 size={19} aria-hidden="true" />}
        </button>
        <button ref={fullscreenButtonRef} type="button" aria-label={fullscreenMode === 'NONE' ? 'Enter video fullscreen' : 'Exit video fullscreen'}
          onPointerDown={event => event.stopPropagation()} onPointerUp={event => event.stopPropagation()} onClick={handleFullscreenClick}>
          <Maximize2 size={18} aria-hidden="true" />
        </button>
      </div>
      {playbackError && <span className="tenant-v0-quick-player-error" role="alert">{playbackError}</span>}
    </div>
  </div>;
};
