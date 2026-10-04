import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Home, Play } from 'lucide-react';
import type { Property } from '../types';
import { propertyService } from '../services/propertyService';
import { resolvePropertyVideoUrl } from '../utils/propertyVideo';
import { buildCloudinaryUrl } from '../utils/mediaTransform';

interface TenantDiscoveryCardMediaProps {
  property: Property;
  reduceMotion: boolean;
  onOpen: () => void;
  openerId: string;
}

/** Discovery pages carry a video flag but not its URL; resolve the real URL only on desktop hover. */
export const TenantDiscoveryCardMedia: React.FC<TenantDiscoveryCardMediaProps> = ({
  property, reduceMotion, onOpen, openerId
}) => {
  const [hovered, setHovered] = useState(false);
  const [videoUrl, setVideoUrl] = useState(() => resolvePropertyVideoUrl(property));
  const [videoPlaying, setVideoPlaying] = useState(false);
  const [videoFailed, setVideoFailed] = useState(false);
  const [imageFailed, setImageFailed] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const requestRef = useRef<AbortController | null>(null);

  useEffect(() => () => requestRef.current?.abort(), []);

  const requestVideoUrl = useCallback(() => {
    if (videoUrl || property._hasVideo !== true || requestRef.current) return;
    const controller = new AbortController();
    requestRef.current = controller;
    propertyService.getPublicProperty(property.id, controller.signal)
      .then(detail => {
        if (controller.signal.aborted) return;
        setVideoUrl(resolvePropertyVideoUrl(detail));
      })
      .catch(() => {
        // The card remains usable when detail media cannot be resolved; a later hover can retry.
      })
      .finally(() => {
        if (requestRef.current === controller) requestRef.current = null;
      });
  }, [property._hasVideo, property.id, videoUrl]);

  const handleMouseEnter = () => {
    if (reduceMotion || typeof window === 'undefined'
      || !window.matchMedia('(hover: hover) and (pointer: fine)').matches) return;
    setHovered(true);
    requestVideoUrl();
  };

  useEffect(() => {
    const video = videoRef.current;
    if (!video) {
      setVideoPlaying(false);
      return;
    }
    if (hovered && videoUrl && !videoFailed) {
      video.muted = true;
      void video.play().then(() => {
        if (!video.paused) setVideoPlaying(true);
      }).catch(() => setVideoPlaying(false));
    } else {
      video.pause();
      setVideoPlaying(false);
    }
  }, [hovered, videoFailed, videoUrl]);

  const coverImage = property.images?.[0]?.trim();
  useEffect(() => setImageFailed(false), [coverImage]);
  return <div className="absolute inset-0 overflow-hidden bg-[#e8e6df]"
    onMouseEnter={handleMouseEnter} onMouseLeave={() => {
      setHovered(false);
      setVideoPlaying(false);
      videoRef.current?.pause();
    }}>
    {coverImage && !imageFailed ? <img src={buildCloudinaryUrl(coverImage, 'DISCOVERY_CARD')} alt=""
      loading="lazy" decoding="async"
      onError={() => setImageFailed(true)}
      className={`h-full w-full object-cover transition-transform duration-500 motion-reduce:transition-none ${hovered && !videoPlaying ? 'scale-[1.025]' : ''}`} />
      : <div className="flex h-full w-full items-center justify-center text-slate-500" role="img" aria-label="Property photo unavailable"><Home size={36} aria-hidden="true" /></div>}
    {videoUrl && hovered && !videoFailed && <video ref={videoRef} src={videoUrl} muted loop playsInline preload="none"
      aria-label={`${property.title || 'Property'} video preview`} onPlaying={() => setVideoPlaying(true)}
      onError={() => { setVideoFailed(true); setVideoPlaying(false); }}
      className={`absolute inset-0 h-full w-full object-cover transition-opacity duration-200 motion-reduce:transition-none ${videoPlaying ? 'opacity-100' : 'opacity-0'}`} />}
    {videoUrl && hovered && !videoPlaying && !videoFailed && <span aria-hidden="true" className="pointer-events-none absolute inset-0 grid place-items-center bg-slate-950/10">
      <span className="grid h-12 w-12 place-items-center rounded-full border border-white/70 bg-slate-950/55 text-white shadow-lg backdrop-blur-sm"><Play size={19} fill="currentColor" /></span>
    </span>}
    <button id={openerId} type="button" onClick={onOpen} aria-label={`Quick view: ${property.title || 'property'}`} className="absolute inset-0 h-full w-full focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald-700" />
  </div>;
};
