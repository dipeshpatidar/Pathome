import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useNavigationType } from 'react-router-dom';
import {
  Bath, BedDouble, Building2, Check, ChevronDown, ChevronLeft, ChevronRight, CircleAlert,
  Dumbbell, Image as LucideImage, LayoutDashboard, LoaderCircle, MapPin,
  Maximize2, Minimize2, Play, Ruler, Sofa, UtensilsCrossed,
  Video, WalletCards, Wind, X
} from 'lucide-react';
import { Property, RoomTag } from '../types';
import { propertyService } from '../services/propertyService';
import { buildCloudinaryUrl, deriveVideoPosterUrl } from '../utils/mediaTransform';
import { getMediaTagIcon, getMediaTagLabel, getTaggedAreas } from '../utils/mediaTags';

type GalleryMedia = { url: string; type: 'IMAGE' | 'VIDEO'; tagLabel: string | null; roomTag?: string | null };

/** Map from icon name string to Lucide component for dynamic dispatch. */
const LUCIDE_TAG_ICONS: Record<string, React.ElementType> = {
  Sofa, BedDouble, Bath, UtensilsCrossed, Building2, Wind, Dumbbell, LayoutDashboard, Image: LucideImage,
};

const LABEL_TO_TAG: Record<string, RoomTag> = {
  'Living Room': 'LIVING_ROOM',
  'Master Bedroom': 'MASTER_BEDROOM',
  'Bedroom': 'BEDROOM',
  'Kitchen': 'KITCHEN',
  'Bathroom': 'BATHROOM',
  'Balcony': 'BALCONY',
  'Exterior': 'EXTERIOR',
  'Amenities': 'AMENITIES',
  'Floor Plan': 'FLOOR_PLAN',
};

interface AreaNavigationDropdownProps {
  areas: Array<[string, number]>;
  currentLabel: string | null;
  currentRoomTag?: string | null;
  onSelect: (index: number) => void;
}

/**
 * Premium translucent "Jump to area" dropdown overlaid directly on the media stage (top-left).
 * Closed state: [ icon  Bathroom  ▾ ] — compact glass pill.
 * Open state: floating dark glass listbox menu with semantic room icons and active indicators.
 */
const AreaNavigationDropdown: React.FC<AreaNavigationDropdownProps> = ({
  areas,
  currentLabel,
  currentRoomTag,
  onSelect
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [focusedIndex, setFocusedIndex] = useState(-1);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionsRef = useRef<Array<HTMLButtonElement | null>>([]);

  const isInteractive = areas.length > 1 || (areas.length === 1 && !currentLabel);

  // Determine current icon
  const currentIconName = getMediaTagIcon(currentRoomTag || (currentLabel ? LABEL_TO_TAG[currentLabel] : null));
  const CurrentIcon = LUCIDE_TAG_ICONS[currentIconName] ?? LayoutDashboard;
  const displayLabel = currentLabel || 'Jump to area';

  // Close when clicking outside
  useEffect(() => {
    if (!isOpen) return;
    const handlePointerDown = (e: MouseEvent | TouchEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('touchstart', handlePointerDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('touchstart', handlePointerDown);
    };
  }, [isOpen]);

  // Sync focusedIndex when opening
  useEffect(() => {
    if (isOpen) {
      const selectedIdx = areas.findIndex(([label]) => label === currentLabel);
      setFocusedIndex(selectedIdx >= 0 ? selectedIdx : 0);
    }
  }, [isOpen, areas, currentLabel]);

  // Focus the option element when focusedIndex changes
  useEffect(() => {
    if (isOpen && focusedIndex >= 0 && optionsRef.current[focusedIndex]) {
      optionsRef.current[focusedIndex]?.focus();
    }
  }, [isOpen, focusedIndex]);

  // Keyboard navigation on listbox
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!isOpen) {
      if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        setIsOpen(true);
      }
      return;
    }

    if (e.key === 'Escape') {
      e.preventDefault();
      setIsOpen(false);
      triggerRef.current?.focus();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setFocusedIndex((prev) => (prev + 1) % areas.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setFocusedIndex((prev) => (prev - 1 + areas.length) % areas.length);
    } else if (e.key === 'Home') {
      e.preventDefault();
      setFocusedIndex(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      setFocusedIndex(areas.length - 1);
    } else if (e.key === 'Tab') {
      setIsOpen(false);
    }
  };

  const handleSelectArea = (index: number) => {
    onSelect(index);
    setIsOpen(false);
    triggerRef.current?.focus();
  };

  // If no areas exist and no current label, don't render anything
  if (areas.length === 0 && !currentLabel) return null;

  // Single area and currently on that area: render non-interactive premium chip
  if (!isInteractive) {
    return (
      <div className="relative inline-block">
        <span className="inline-flex min-h-[36px] max-w-[calc(100vw-110px)] sm:max-w-[260px] md:max-w-[300px] items-center gap-2 rounded-full border border-white/20 bg-slate-950/70 pl-3 pr-3.5 py-1.5 text-xs sm:text-sm font-semibold leading-none text-white/95 shadow-lg shadow-black/30 backdrop-blur-md">
          <CurrentIcon className="h-3.5 w-3.5 sm:h-4 sm:w-4 shrink-0 text-white/85" aria-hidden="true" />
          <span className="truncate max-w-[130px] sm:max-w-[180px] md:max-w-[220px]">{displayLabel}</span>
        </span>
      </div>
    );
  }

  return (
    <div ref={containerRef} className="relative inline-block" onKeyDown={handleKeyDown}>
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        aria-label="Jump to area"
        title="Jump to area"
        onClick={() => setIsOpen((prev) => !prev)}
        className="inline-flex min-h-[36px] max-w-[calc(100vw-110px)] sm:max-w-[260px] md:max-w-[300px] items-center gap-2 rounded-full border border-white/20 bg-slate-950/70 pl-3 pr-2.5 py-1.5 text-xs sm:text-sm font-semibold text-white/95 shadow-lg shadow-black/30 backdrop-blur-md transition-all hover:bg-slate-900/85 hover:border-white/35 active:scale-98 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950"
      >
        <CurrentIcon className="h-3.5 w-3.5 sm:h-4 sm:w-4 shrink-0 text-white/85" aria-hidden="true" />
        <span className="truncate max-w-[130px] sm:max-w-[180px] md:max-w-[220px]">{displayLabel}</span>
        <ChevronDown
          className={`h-3.5 w-3.5 sm:h-4 sm:w-4 shrink-0 text-white/70 transition-transform duration-200 ${isOpen ? 'rotate-180' : ''}`}
          aria-hidden="true"
        />
      </button>

      {isOpen && (
        <div
          role="listbox"
          aria-label="Jump to area"
          className="absolute left-0 top-full mt-2 w-max min-w-[170px] max-w-[260px] sm:max-w-[300px] max-h-[220px] sm:max-h-[260px] overflow-y-auto rounded-2xl border border-white/20 bg-slate-950/92 p-1.5 shadow-2xl backdrop-blur-xl z-50"
        >
          {areas.map(([label, mediaIndex], idx) => {
            const isSelected = currentLabel === label;
            const areaIconName = getMediaTagIcon(LABEL_TO_TAG[label]);
            const AreaIcon = LUCIDE_TAG_ICONS[areaIconName] ?? LayoutDashboard;

            return (
              <button
                key={label}
                ref={(el) => { optionsRef.current[idx] = el; }}
                type="button"
                role="option"
                aria-selected={isSelected}
                tabIndex={focusedIndex === idx ? 0 : -1}
                onClick={() => handleSelectArea(mediaIndex)}
                className={`flex w-full items-center gap-2.5 rounded-xl px-2.5 py-2 text-xs sm:text-sm transition-colors text-left focus:outline-none focus-visible:ring-1 focus-visible:ring-emerald-400 min-h-[38px] ${
                  isSelected
                    ? 'bg-emerald-500/20 text-emerald-300 font-semibold border border-emerald-500/30'
                    : 'text-white/85 hover:bg-white/10 hover:text-white'
                }`}
              >
                <AreaIcon className={`h-3.5 w-3.5 sm:h-4 sm:w-4 shrink-0 ${isSelected ? 'text-emerald-400' : 'text-white/75'}`} aria-hidden="true" />
                <span className="truncate flex-1 text-left">{label}</span>
                {isSelected && <Check className="h-3.5 w-3.5 shrink-0 text-emerald-400 ml-auto" aria-hidden="true" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
};

interface PublicPropertyDetailProps {
  propertyId: number | null;
  onRequestVisit: (property: Property) => void;
}

const formatRupees = (amount?: number | null) => amount && amount > 0 ? `₹${amount.toLocaleString('en-IN')}` : null;

const floorLabel = (property: Property) => {
  if (property.floor === null || property.floor === undefined) return null;
  const floor = property.floor === 0 ? 'Ground floor' : `${property.floor}${property.floor === 1 ? 'st' : property.floor === 2 ? 'nd' : property.floor === 3 ? 'rd' : 'th'} floor`;
  return property.totalFloors ? `${floor} of ${property.totalFloors}` : floor;
};

/** Lightbox modal for full-screen media inspection. */
const Lightbox: React.FC<{
  media: GalleryMedia[];
  startIndex: number;
  title: string;
  onClose: () => void;
}> = ({ media, startIndex, title, onClose }) => {
  const [idx, setIdx] = useState(startIndex);
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeBtnRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      if (e.key === 'ArrowRight') setIdx(i => (i + 1) % media.length);
      if (e.key === 'ArrowLeft') setIdx(i => (i - 1 + media.length) % media.length);
    };
    document.addEventListener('keydown', onKey);
    // Lock background scroll
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [media.length, onClose]);

  useEffect(() => { setIdx(startIndex); }, [startIndex]);

  const current = media[idx];
  const lightboxImageUrl = buildCloudinaryUrl(current.type === 'IMAGE' ? current.url : null, 'FULLSCREEN') || current.url;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`${title} — ${current.tagLabel ? `${current.tagLabel}, ` : ''}media ${idx + 1} of ${media.length}`}
      className="fixed inset-0 z-[9999] flex flex-col items-center justify-center bg-black/95 p-4"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      {/* Close */}
      <button
        ref={closeBtnRef}
        type="button"
        aria-label="Close media viewer"
        onClick={onClose}
        className="absolute right-4 top-4 flex h-11 w-11 items-center justify-center rounded-full bg-white/10 text-white hover:bg-white/25 transition"
      >
        <X className="h-6 w-6" />
      </button>

      {/* Media */}
      <div className="relative flex max-h-[82vh] max-w-[92vw] items-center justify-center">
        {current.type === 'VIDEO' ? (
          <video
            controls
            aria-label={current.tagLabel ? `${title} — ${current.tagLabel} video` : `${title} video`}
            playsInline
            poster={deriveVideoPosterUrl(current.url) || undefined}
            className="max-h-[82vh] max-w-[92vw] rounded-xl"
            src={current.url}
          />
        ) : (
          // Blurred backdrop layer
          <>
            <img
              src={lightboxImageUrl || current.url}
              alt=""
              aria-hidden="true"
              className="pointer-events-none absolute inset-0 h-full w-full scale-110 object-cover opacity-20 blur-2xl rounded-xl"
            />
            <img
              src={lightboxImageUrl || current.url}
              alt={`${title} — ${current.tagLabel ? `${current.tagLabel}, ` : ''}photo ${idx + 1}`}
              className="relative max-h-[82vh] max-w-[92vw] rounded-xl object-contain"
            />
          </>
        )}
      </div>

      {/* Navigation */}
      {media.length > 1 && (
        <>
          <button
            type="button"
            aria-label="Previous photo"
            onClick={() => setIdx(i => (i - 1 + media.length) % media.length)}
            className="absolute left-3 top-1/2 flex h-12 w-12 -translate-y-1/2 items-center justify-center rounded-full bg-white/15 text-white hover:bg-white/30 transition"
          >
            <ChevronLeft className="h-6 w-6" />
          </button>
          <button
            type="button"
            aria-label="Next photo"
            onClick={() => setIdx(i => (i + 1) % media.length)}
            className="absolute right-3 top-1/2 flex h-12 w-12 -translate-y-1/2 items-center justify-center rounded-full bg-white/15 text-white hover:bg-white/30 transition"
          >
            <ChevronRight className="h-6 w-6" />
          </button>
          <p className="mt-3 text-sm font-semibold text-white/80">{current.tagLabel ? `${current.tagLabel} · ` : ''}{idx + 1} / {media.length}</p>
        </>
      )}
      {media.length === 1 && current.tagLabel && <p className="mt-3 text-sm font-semibold text-white/80">{current.tagLabel}</p>}
    </div>
  );
};

export const PublicPropertyDetail: React.FC<PublicPropertyDetailProps> = ({ propertyId, onRequestVisit }) => {
  const navigate = useNavigate();
  const [property, setProperty] = useState<Property | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeMedia, setActiveMedia] = useState(0);
  const [lightboxOpen, setLightboxOpen] = useState(false);
  const [isVideoPlaying, setIsVideoPlaying] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const mediaStageRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setIsVideoPlaying(false);
  }, [activeMedia]);

  // Track fullscreen state changes (including browser Esc key)
  useEffect(() => {
    const onFsChange = () => {
      setIsFullscreen(
        !!(document.fullscreenElement ||
          (document as any).webkitFullscreenElement ||
          (document as any).mozFullScreenElement)
      );
    };
    document.addEventListener('fullscreenchange', onFsChange);
    document.addEventListener('webkitfullscreenchange', onFsChange);
    document.addEventListener('mozfullscreenchange', onFsChange);
    return () => {
      document.removeEventListener('fullscreenchange', onFsChange);
      document.removeEventListener('webkitfullscreenchange', onFsChange);
      document.removeEventListener('mozfullscreenchange', onFsChange);
    };
  }, []);

  const requestFullscreen = useCallback((el: HTMLElement | null) => {
    if (!el) return;
    if (el.requestFullscreen) el.requestFullscreen();
    else if ((el as any).webkitRequestFullscreen) (el as any).webkitRequestFullscreen();
    else if ((el as any).mozRequestFullScreen) (el as any).mozRequestFullScreen();
  }, []);

  const exitFullscreen = useCallback(() => {
    if (document.exitFullscreen) document.exitFullscreen();
    else if ((document as any).webkitExitFullscreen) (document as any).webkitExitFullscreen();
    else if ((document as any).mozCancelFullScreen) (document as any).mozCancelFullScreen();
  }, []);

  const toggleFullscreen = useCallback(() => {
    if (isFullscreen) {
      exitFullscreen();
    } else {
      requestFullscreen(mediaStageRef.current);
    }
  }, [isFullscreen, requestFullscreen, exitFullscreen]);

  const [isScrolled, setIsScrolled] = useState(() => (typeof window !== 'undefined' ? window.scrollY > 60 : false));
  const navigationType = useNavigationType();

  // Ensure new navigations (PUSH) to property detail display the top of the page before first paint,
  // while preserving browser Back/Forward (POP) history restoration.
  useLayoutEffect(() => {
    if (navigationType !== 'POP') {
      window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
      setIsScrolled(false);
    }
  }, [propertyId, navigationType]);

  useEffect(() => {
    const onScroll = () => {
      setIsScrolled(window.scrollY > 60);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const handleBackToDiscovery = () => {
    if (window.history.state && window.history.state.idx > 0) {
      navigate(-1);
    } else {
      navigate('/');
    }
  };

  useEffect(() => {
    if (propertyId === null) {
      setProperty(null);
      setError('This property link is invalid.');
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    setError(null);
    propertyService.getPublicProperty(propertyId)
      .then((result) => { if (active) { setProperty(result); setActiveMedia(0); } })
      .catch((requestError: any) => { if (active) setError(requestError?.message || 'Unable to load this property.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [propertyId]);

  const media = useMemo(() => {
    if (!property) return [] as GalleryMedia[];
    const safeTaggedMedia = property.taggedMedia?.filter((entry) => entry.mediaUrl?.trim()) || [];
    if (safeTaggedMedia.length > 0) {
      return safeTaggedMedia.map((entry) => ({
        url: entry.mediaUrl,
        tagLabel: getMediaTagLabel(entry.roomTag),
        roomTag: entry.roomTag ?? null,
        type: entry.mediaType === 'VIDEO_WALKTHROUGH'
          || /\.(mp4|webm|mov)(?:[?#]|$)/i.test(entry.mediaUrl) ? 'VIDEO' as const : 'IMAGE' as const
      }));
    }
    return [
      ...property.images.map((url) => ({ url, type: 'IMAGE' as const, tagLabel: null, roomTag: null })),
      ...(property.videoUrl ? [{ url: property.videoUrl, type: 'VIDEO' as const, tagLabel: null, roomTag: null }] : [])
    ];
  }, [property]);

  const taggedAreas = useMemo(() => getTaggedAreas(media), [media]);

  const openLightbox = useCallback((index: number) => {
    if (media[index]?.type === 'IMAGE') {
      setLightboxOpen(true);
      setActiveMedia(index);
    }
  }, [media]);

  // Navigation handlers with video pause & event isolation
  const handlePrevMedia = useCallback((e?: React.MouseEvent) => {
    if (e) {
      e.stopPropagation();
      e.preventDefault();
    }
    if (videoRef.current) {
      videoRef.current.pause();
      setIsVideoPlaying(false);
    }
    setActiveMedia((prev) => (media.length > 0 ? (prev - 1 + media.length) % media.length : 0));
  }, [media.length]);

  const handleNextMedia = useCallback((e?: React.MouseEvent) => {
    if (e) {
      e.stopPropagation();
      e.preventDefault();
    }
    if (videoRef.current) {
      videoRef.current.pause();
      setIsVideoPlaying(false);
    }
    setActiveMedia((prev) => (media.length > 0 ? (prev + 1) % media.length : 0));
  }, [media.length]);

  if (loading) {
    return (
      <main className="mx-auto w-full max-w-7xl px-4 pt-3 pb-28 sm:px-6 sm:pt-4 sm:pb-8 lg:px-8 lg:pb-10" aria-label="Loading property details" aria-busy="true">
        {/* Navigation placeholder: exact height to prevent layout shift */}
        <div className="mb-4 flex h-11 items-center justify-between">
          <div className="inline-flex h-11 items-center gap-2 rounded-full border border-white/20 bg-slate-950/80 px-4 py-2.5 text-xs font-bold text-white shadow-xl shadow-black/35 backdrop-blur-2xl">
            <ChevronLeft className="h-4.5 w-4.5 shrink-0 text-white/80" />
            <span>Back to discovery</span>
          </div>
          <div className="hidden h-4 w-40 animate-pulse rounded bg-slate-200 sm:block" />
        </div>

        {/* 12-column grid skeleton: matching exact loaded layout */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-12 lg:items-start">
          <section className="space-y-4 lg:col-span-7">
            {/* Stable fixed-height media container skeleton */}
            <div className="relative flex h-[340px] sm:h-[420px] lg:h-[480px] w-full items-center justify-center overflow-hidden rounded-3xl border border-slate-800 bg-slate-950 shadow-xl shadow-slate-900/10">
              <div className="flex flex-col items-center gap-3">
                <LoaderCircle className="h-7 w-7 animate-spin text-emerald-500" />
                <span className="text-xs font-semibold text-slate-400">Loading gallery…</span>
              </div>
            </div>
            {/* Thumbnail skeleton strip */}
            <div className="flex gap-2 overflow-hidden pb-1">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="h-16 w-20 shrink-0 animate-pulse rounded-xl bg-slate-200" />
              ))}
            </div>
          </section>

          {/* Sticky sidebar skeleton */}
          <aside className="space-y-5 lg:col-span-5">
            <div className="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-7">
              <div className="h-3 w-28 animate-pulse rounded bg-emerald-100" />
              <div className="mt-3 h-7 w-3/4 animate-pulse rounded-lg bg-slate-200" />
              <div className="mt-3 h-4 w-1/2 animate-pulse rounded bg-slate-100" />
              <div className="mt-6 grid grid-cols-2 gap-3 border-y border-slate-100 py-5">
                <div className="space-y-2">
                  <div className="h-3 w-20 animate-pulse rounded bg-slate-100" />
                  <div className="h-6 w-24 animate-pulse rounded bg-slate-200" />
                </div>
                <div className="space-y-2">
                  <div className="h-3 w-20 animate-pulse rounded bg-slate-100" />
                  <div className="h-6 w-24 animate-pulse rounded bg-slate-200" />
                </div>
              </div>
              <div className="mt-6 h-12 w-full animate-pulse rounded-xl bg-slate-200" />
            </div>
          </aside>
        </div>
      </main>
    );
  }
  if (error || !property) {
    return <main className="mx-auto flex min-h-[60vh] max-w-xl items-center px-4"><div className="w-full rounded-3xl border border-rose-200 bg-white p-6 text-center shadow-sm"><CircleAlert className="mx-auto h-10 w-10 text-rose-600" /><h1 className="mt-3 font-['Outfit'] text-2xl font-black text-slate-900">Property unavailable</h1><p className="mt-2 text-sm leading-relaxed text-slate-600">{error || 'This property is no longer available.'}</p><Link to="/" className="mt-5 inline-flex min-h-11 items-center rounded-xl bg-slate-900 px-4 py-3 text-sm font-bold text-white">Back to discovery</Link></div></main>;
  }

  const currentMedia = media[activeMedia];
  const amenities = property.amenities?.split(',').map((item) => item.trim()).filter(Boolean) || [];
  const preferredTenant = property.preferredTenant?.split(',').map((item) => item.replace(/_/g, ' ').toLowerCase()).join(', ');

  return (
    <>
      {lightboxOpen && (
        <Lightbox
          media={media}
          startIndex={activeMedia}
          title={property.title}
          onClose={() => setLightboxOpen(false)}
        />
      )}
      <main className="mx-auto w-full max-w-7xl px-4 pt-3 pb-28 sm:px-6 sm:pt-4 sm:pb-8 lg:px-8 lg:pb-10">
        {/* Dependable High-Contrast Floating Back Navigation */}
        <div className="mb-4 flex h-11 items-center justify-between">
          <button
            type="button"
            onClick={handleBackToDiscovery}
            aria-label="Back to discovery"
            title="Back to discovery"
            className={`group inline-flex items-center rounded-full border border-white/25 ring-1 ring-black/40 bg-slate-950/80 text-white shadow-xl shadow-black/35 backdrop-blur-2xl transition-all duration-300 ease-out hover:border-emerald-400/50 hover:bg-slate-900 hover:shadow-2xl hover:shadow-black/50 hover:text-white active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950 ${
              isScrolled
                ? 'fixed top-[84px] left-4 sm:left-6 lg:left-8 z-50 h-11 w-11 min-h-[44px] min-w-[44px] p-0 justify-center sm:h-auto sm:w-auto sm:px-4 sm:py-2.5 sm:min-w-0 sm:gap-2 text-xs font-bold'
                : 'min-h-[44px] px-4 py-2.5 gap-2 text-xs font-bold'
            }`}
          >
            <ChevronLeft className="h-4.5 w-4.5 shrink-0 text-white transition-transform duration-200 group-hover:-translate-x-0.5 group-hover:text-emerald-400" />
            <span className={isScrolled ? 'hidden sm:inline' : 'inline'}>
              Back to discovery
            </span>
          </button>
          <div className="hidden sm:flex items-center gap-1.5 text-xs font-medium text-slate-500">
            <span>Discovery</span>
            <span>/</span>
            <span className="text-slate-700">{property.city}</span>
            {property.sector && (
              <>
                <span>/</span>
                <span className="text-emerald-700 font-bold">{property.sector}</span>
              </>
            )}
          </div>
        </div>
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-12 lg:items-start">
          <section className="space-y-4 lg:col-span-7">
            {/* Main viewer: Fixed responsive height prevents CLS; dark containment preserves aspect ratios */}
            <div
              ref={mediaStageRef}
              className="relative h-[340px] sm:h-[420px] lg:h-[480px] w-full overflow-hidden rounded-3xl border border-slate-200 bg-slate-950 shadow-xl shadow-slate-900/10"
            >
              <div className="flex h-full w-full items-center justify-center">
                {currentMedia ? (
                  currentMedia.type === 'VIDEO' ? (
                    (() => {
                      const poster = deriveVideoPosterUrl(currentMedia.url);
                      const togglePlay = () => {
                        if (videoRef.current) {
                          if (videoRef.current.paused) {
                            videoRef.current.play().then(() => setIsVideoPlaying(true)).catch(() => {});
                          } else {
                            videoRef.current.pause();
                            setIsVideoPlaying(false);
                          }
                        }
                      };

                      return (
                        <div className="relative flex h-full w-full items-center justify-center overflow-hidden bg-slate-950">
                          {/* Ambient blurred backdrop */}
                          {poster && (
                            <img
                              src={poster}
                              alt=""
                              aria-hidden="true"
                              className="pointer-events-none absolute inset-0 h-full w-full scale-110 object-cover opacity-25 blur-2xl"
                            />
                          )}
                          {/*
                            Video element with native controls.
                            z-10 keeps it above the backdrop.
                            The center play-button overlay is z-20 but positioned
                            to NOT cover the bottom native control bar (bottom-14),
                            and with pointer-events-none once playing so controls
                            remain fully interactive at all times.
                          */}
                          <video
                            ref={videoRef}
                            controls
                            aria-label={currentMedia.tagLabel ? `${property.title} — ${currentMedia.tagLabel} video` : `${property.title} video`}
                            playsInline
                            preload="metadata"
                            poster={poster || undefined}
                            src={currentMedia.url}
                            onPlay={() => setIsVideoPlaying(true)}
                            onPause={() => setIsVideoPlaying(false)}
                            onEnded={() => setIsVideoPlaying(false)}
                            className="relative z-10 max-h-full w-auto max-w-full object-contain shadow-2xl"
                          />
                          {/* Center Play affordance — visible only when paused, does NOT cover control bar */}
                          {!isVideoPlaying && (
                            <button
                              type="button"
                              aria-label="Play video walkthrough"
                              onClick={(e) => {
                                e.stopPropagation();
                                togglePlay();
                              }}
                              className="pointer-events-auto absolute bottom-16 left-1/2 z-20 flex h-16 w-16 -translate-x-1/2 items-center justify-center rounded-full bg-emerald-600/90 text-white shadow-2xl backdrop-blur-sm transition-all hover:scale-110 hover:bg-emerald-500 active:scale-95 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-emerald-400"
                            >
                              <Play className="ml-1 h-7 w-7 fill-current" />
                            </button>
                          )}
                        </div>
                      );
                    })()
                  ) : (
                    /* Image: contain preserves full content. Click to expand in lightbox. */
                    <button
                      type="button"
                      aria-label={currentMedia.tagLabel ? `Expand ${currentMedia.tagLabel} image` : 'Expand image'}
                      onClick={() => openLightbox(activeMedia)}
                      className="relative flex h-full w-full items-center justify-center cursor-zoom-in"
                    >
                      {/* Blurred backdrop for portrait/mismatched-ratio images */}
                      <img
                        src={buildCloudinaryUrl(currentMedia.url, 'DETAIL_THUMBNAIL') || currentMedia.url}
                        alt=""
                        aria-hidden="true"
                        className="pointer-events-none absolute inset-0 h-full w-full scale-105 object-cover opacity-20 blur-2xl"
                      />
                      <img
                        src={buildCloudinaryUrl(currentMedia.url, 'DETAIL_MAIN') || currentMedia.url}
                        alt={`${property.title} — ${currentMedia.tagLabel ? `${currentMedia.tagLabel}, ` : ''}photo ${activeMedia + 1}`}
                        className="relative max-h-full w-full object-contain"
                        loading="lazy"
                      />
                    </button>
                  )
                ) : (
                  <div className="flex h-64 items-center justify-center text-sm font-semibold text-slate-300">Photos will be available soon.</div>
                )}
              </div>

              {/* Premium translucent Jump-to-Area dropdown — top-left of media stage */}
              <div className="absolute left-4 top-4 z-30">
                <AreaNavigationDropdown
                  areas={taggedAreas}
                  currentLabel={currentMedia?.tagLabel ?? null}
                  currentRoomTag={currentMedia?.roomTag ?? null}
                  onSelect={(idx) => {
                    if (videoRef.current) {
                      videoRef.current.pause();
                      setIsVideoPlaying(false);
                    }
                    setActiveMedia(idx);
                  }}
                />
              </div>

              {/* Fullscreen button — top-right */}
              {typeof document !== 'undefined' && 'fullscreenEnabled' in document && (document.fullscreenEnabled || (document as any).webkitFullscreenEnabled) && (
                <button
                  type="button"
                  aria-label={isFullscreen ? 'Exit fullscreen' : 'Enter fullscreen'}
                  onClick={toggleFullscreen}
                  className="absolute right-4 top-4 z-30 flex h-9 w-9 items-center justify-center rounded-full border border-white/20 bg-slate-950/70 text-white shadow-md backdrop-blur-md transition-all hover:bg-slate-900 active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                >
                  {isFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
                </button>
              )}

              {/* Navigation arrows with z-40 and explicit event isolation */}
              {media.length > 1 && (
                <>
                  <button
                    type="button"
                    aria-label="Previous property media"
                    onClick={handlePrevMedia}
                    className="absolute left-3 top-1/2 z-40 flex h-11 w-11 -translate-y-1/2 pointer-events-auto items-center justify-center rounded-full border border-white/20 bg-slate-950/75 text-white shadow-xl backdrop-blur-md transition-all hover:scale-105 hover:bg-slate-900 active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                  >
                    <ChevronLeft className="h-5 w-5" />
                  </button>
                  <button
                    type="button"
                    aria-label="Next property media"
                    onClick={handleNextMedia}
                    className="absolute right-3 top-1/2 z-40 flex h-11 w-11 -translate-y-1/2 pointer-events-auto items-center justify-center rounded-full border border-white/20 bg-slate-950/75 text-white shadow-xl backdrop-blur-md transition-all hover:scale-105 hover:bg-slate-900 active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                  >
                    <ChevronRight className="h-5 w-5" />
                  </button>
                </>
              )}
            </div>
            {/* Thumbnail strip: images lazy-loaded with Cloudinary DETAIL_THUMBNAIL preset, video shows icon indicator */}
            {media.length > 1 && (
              <div className="flex gap-2 overflow-x-auto pb-1" aria-label="Property media thumbnails">
                {media.map((entry, index) => (
                  <button
                    key={`${entry.url}-${index}`}
                    type="button"
                    onClick={() => setActiveMedia(index)}
                    aria-label={`Show ${entry.tagLabel ? `${entry.tagLabel} ` : ''}${entry.type === 'VIDEO' ? 'video' : 'photo'} ${index + 1}`}
                    title={entry.tagLabel || undefined}
                    className={`relative h-16 w-20 shrink-0 overflow-hidden rounded-xl border-2 ${index === activeMedia ? 'border-emerald-600' : 'border-transparent'}`}
                  >
                    {entry.type === 'VIDEO' ? (
                      <div className="flex h-full w-full items-center justify-center bg-slate-900 text-white">
                        <Video className="h-5 w-5" />
                      </div>
                    ) : (
                      <img
                        src={buildCloudinaryUrl(entry.url, 'DETAIL_THUMBNAIL') || entry.url}
                        alt=""
                        loading="lazy"
                        className="h-full w-full object-cover"
                      />
                    )}
                  </button>
                ))}
              </div>
            )}
          </section>

          <aside className="space-y-5 lg:col-span-5 lg:sticky lg:top-24">
            <div className="rounded-3xl border border-slate-200 bg-white p-5 shadow-sm sm:p-7">
              <p className="text-xs font-bold uppercase tracking-wider text-emerald-700">Rental property</p>
              <h1 className="mt-2 font-['Outfit'] text-2xl font-black leading-tight text-slate-900 sm:text-3xl">{property.title}</h1>
              <p className="mt-3 flex items-center gap-2 text-sm font-medium text-slate-600"><MapPin className="h-4 w-4 shrink-0 text-emerald-600" />{[property.sector, property.city].filter(Boolean).join(', ') || 'Locality details available on request'}</p>
              <div className="mt-5 grid grid-cols-2 gap-3 border-y border-slate-100 py-5"><div><p className="text-xs font-bold uppercase tracking-wider text-slate-400">Monthly rent</p><p className="mt-1 font-mono text-xl font-black text-slate-900">{formatRupees(property.monthlyRent) || 'On request'}</p></div>{formatRupees(property.securityDeposit) && <div><p className="text-xs font-bold uppercase tracking-wider text-slate-400">Security deposit</p><p className="mt-1 font-mono text-lg font-black text-emerald-700">{formatRupees(property.securityDeposit)}</p></div>}</div>
              {formatRupees(property.maintenanceCharge) && <p className="mt-4 flex items-center gap-2 text-sm text-slate-600"><WalletCards className="h-4 w-4 text-emerald-600" />Maintenance: {formatRupees(property.maintenanceCharge)} / month</p>}
              <button type="button" onClick={() => onRequestVisit(property)} className="mt-6 hidden min-h-12 w-full items-center justify-center rounded-xl bg-emerald-600 px-4 py-3 text-sm font-black text-white shadow-lg shadow-emerald-600/25 transition hover:bg-emerald-700 lg:flex">Request a Visit</button>
              <p className="mt-3 text-xs leading-relaxed text-slate-500">Your request is reviewed for availability before any Visit Session is confirmed.</p>
              <p className="mt-2 text-xs leading-relaxed text-slate-500">Interested in this property? Sign in to request a visit and manage your visit requests.</p>
            </div>
          </aside>
        </div>

        <section className="mt-7 grid grid-cols-1 gap-5 lg:grid-cols-3">
          <div className="rounded-3xl border border-slate-200 bg-white p-5 lg:col-span-2">
            <h2 className="font-['Outfit'] text-xl font-black text-slate-900">About this property</h2>
            {property.description && <p className="mt-3 whitespace-pre-line text-sm leading-7 text-slate-600">{property.description}</p>}
            <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-3">
              {property.bhk && <DetailFact icon={<BedDouble />} label="Configuration" value={property.bhk} />}
              {property.propertyType && <DetailFact icon={<Building2 />} label="Property type" value={property.propertyType.replace(/_/g, ' ')} />}
              {property.totalAreaSqFt > 0 && <DetailFact icon={<Ruler />} label="Area" value={`${property.totalAreaSqFt.toLocaleString('en-IN')} sq ft`} />}
              {property.bathroomCount ? <DetailFact icon={<Bath />} label="Bathrooms" value={String(property.bathroomCount)} /> : null}
              {floorLabel(property) && <DetailFact icon={<Building2 />} label="Floor" value={floorLabel(property)!} />}
              {property.furnishingStatus && <DetailFact icon={<Maximize2 />} label="Furnishing" value={property.furnishingStatus} />}
            </div>
          </div>
          <div className="rounded-3xl border border-slate-200 bg-white p-5">
            <h2 className="font-['Outfit'] text-xl font-black text-slate-900">Preferences</h2>
            <dl className="mt-4 space-y-3 text-sm">
              {preferredTenant && <div><dt className="font-bold text-slate-500">Suitable for</dt><dd className="mt-1 capitalize text-slate-800">{preferredTenant}</dd></div>}
              {typeof property.bachelorAllowed === 'boolean' && <div><dt className="font-bold text-slate-500">Bachelor accommodation</dt><dd className="mt-1 text-slate-800">{property.bachelorAllowed ? 'Allowed' : 'Not specified as allowed'}</dd></div>}
              {property.availableFrom && <div><dt className="font-bold text-slate-500">Available from</dt><dd className="mt-1 text-slate-800">{new Date(property.availableFrom).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}</dd></div>}
            </dl>
            {amenities.length > 0 && <><h3 className="mt-6 font-['Outfit'] text-base font-black text-slate-900">Amenities</h3><div className="mt-3 flex flex-wrap gap-2">{amenities.map((amenity) => <span key={amenity} className="rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-900">{amenity}</span>)}</div></>}
          </div>
        </section>
        <div className="safe-area-bottom fixed inset-x-0 bottom-0 z-40 border-t border-slate-200 bg-white/95 p-3 backdrop-blur lg:hidden"><button type="button" onClick={() => onRequestVisit(property)} className="min-h-12 w-full rounded-xl bg-emerald-600 px-4 py-3 text-sm font-black text-white shadow-lg shadow-emerald-600/25">Request a Visit</button></div>
      </main>
    </>
  );
};

const DetailFact: React.FC<{ icon: React.ReactNode; label: string; value: string }> = ({ icon, label, value }) => (
  <div className="flex items-start gap-2 rounded-xl border border-slate-100 bg-slate-50/70 p-3">
    <span className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600">{icon}</span>
    <div><p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">{label}</p><p className="mt-0.5 text-sm font-semibold text-slate-800">{value}</p></div>
  </div>
);
