import React, { useState, useRef, useEffect } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import {
  ArrowRight,
  BedDouble,
  Bookmark,
  Camera,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  MapPin
} from 'lucide-react';
import { Property } from '../types';
import { buildCloudinaryUrl } from '../utils/mediaTransform';
import { formatPropertyArea, formatSecurityDeposit } from '../utils/discoveryCardData';
import { propertyService } from '../services/propertyService';

interface PropertyShowcaseProps {
  properties: Property[];
  onViewDetails: (property: Property) => void;
  onRefineSearch: () => void;
  onOpenMediaModal?: (property: Property, initialMode?: 'VIDEO' | 'PHOTOS') => void;
  selectedSectorFilter?: string;
  selectedCityFilter?: string;
  onSaveFavorite?: (property: Property) => void;
  isLoading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  onClearLocality?: () => void;
}

const videoUrlCache = new Map<number, string>();

const getOrdinal = (n: number): string => {
  const s = ['th', 'st', 'nd', 'rd'];
  const v = n % 100;
  return n + (s[(v - 20) % 10] || s[v] || s[0]);
};

const formatBhkDisplay = (bhk: string | null | undefined): string | null => {
  if (!bhk || !bhk.trim()) return null;
  const clean = bhk.trim();
  if (clean === '0' || clean.toLowerCase() === '0bhk') return null;
  const match = clean.match(/^(\d+)\s*(bhk|rk)$/i);
  return match ? `${match[1]} ${match[2].toUpperCase()}` : clean;
};

const formatFloorDisplay = (floor: number | null | undefined, totalFloors: number | null | undefined): string | null => {
  if (floor === null || floor === undefined) {
    return typeof totalFloors === 'number' && totalFloors > 0 ? `${totalFloors} floors` : null;
  }
  const floorName = floor === 0 ? 'Ground floor' : `${getOrdinal(floor)} floor`;
  return typeof totalFloors === 'number' && totalFloors > 0 ? `${floorName} of ${totalFloors}` : floorName;
};

const formatPreferredTenantDisplay = (pref: string | null | undefined): string | null => {
  if (!pref || !pref.trim()) return null;
  const labels: Record<string, string> = {
    FAMILY: 'Family',
    WORKING_PROFESSIONALS: 'Working professionals',
    BACHELORS: 'Bachelors',
    STUDENTS: 'Students',
    ANY: 'No preference'
  };
  const readable = pref.split(',').map((part) => labels[part.trim().toUpperCase()]).filter(Boolean);
  return readable.length > 0 ? `Preferred: ${readable.join(', ')}` : null;
};

const formatFurnishingDisplay = (value: string | null | undefined): string | null => {
  const normalized = value?.trim().replace(/[_-]/g, ' ').replace(/\s+/g, ' ').toLowerCase();
  const labels: Record<string, string> = {
    'fully furnished': 'Fully furnished',
    'semi furnished': 'Semi-furnished',
    furnished: 'Furnished',
    unfurnished: 'Unfurnished'
  };
  return normalized ? labels[normalized] || null : null;
};

const SkeletonPropertyCard: React.FC = () => (
  <div className="flex min-w-0 w-full flex-col md:max-w-[640px] md:mx-auto lg:max-w-none" aria-hidden="true">
    <div className="aspect-[16/10] rounded-[24px] bg-slate-200/80 sm:aspect-[16/9] animate-pulse motion-reduce:animate-none" />
    <div className="relative z-10 mx-3 -mt-7 flex flex-1 flex-col rounded-[22px] border border-slate-200/80 bg-white p-3.5 shadow-[0_12px_28px_-12px_rgba(15,23,42,0.12)] sm:mx-4 sm:-mt-9 sm:px-4.5 sm:pt-3.5 sm:pb-4 animate-pulse motion-reduce:animate-none">
      <div className="flex items-center justify-between gap-3">
        <div className="h-3.5 w-36 max-w-[55%] rounded bg-slate-200" />
        <div className="h-3.5 w-16 rounded bg-slate-100" />
      </div>
      <div className="mt-2.5 h-5 w-4/5 rounded bg-slate-200 sm:min-h-[2.85rem]" />
      <div className="mt-1.5 h-4 w-2/5 rounded bg-slate-100" />
      <div className="mt-auto pt-3">
        <div className="grid grid-cols-2 gap-4 border-t border-slate-100 pt-2.5">
          <div className="space-y-2"><div className="h-3 w-16 rounded bg-slate-100" /><div className="h-6 w-24 rounded bg-slate-200" /></div>
          <div className="space-y-2"><div className="h-3 w-20 rounded bg-slate-100" /><div className="h-5 w-20 rounded bg-slate-200" /></div>
        </div>
        <div className="mt-2.5 flex items-center justify-between gap-3">
          <div className="h-3 w-32 rounded bg-slate-100" />
          <div className="h-11 w-28 rounded-xl bg-slate-200" />
        </div>
      </div>
    </div>
  </div>
);

interface DiscoveryPropertyCardProps {
  prop: Property;
  index: number;
  reduceMotion: boolean | null;
  onViewDetails: (property: Property) => void;
  onOpenMediaModal?: (property: Property, initialMode?: 'VIDEO' | 'PHOTOS') => void;
  onSaveFavorite?: (property: Property) => void;
  currentImgIdx: number;
  onChangeImage: (direction: -1 | 1, event: React.MouseEvent) => void;
}

const DiscoveryPropertyCard: React.FC<DiscoveryPropertyCardProps> = ({
  prop,
  index,
  reduceMotion,
  onViewDetails,
  onOpenMediaModal,
  onSaveFavorite,
  currentImgIdx,
  onChangeImage
}) => {
  const [isHovered, setIsHovered] = useState<boolean>(false);
  const [isVideoPlaying, setIsVideoPlaying] = useState<boolean>(false);
  const [videoFailed, setVideoFailed] = useState<boolean>(false);
  const [resolvedVideoUrl, setResolvedVideoUrl] = useState<string | null>(() => {
    // 1. Direct legitimate video URL associated with this property
    const directUrl = prop.videoUrl && typeof prop.videoUrl === 'string' && prop.videoUrl.trim()
      ? prop.videoUrl.trim()
      : null;
    // 2. Previously cached real video URL for this exact property
    return directUrl || videoUrlCache.get(prop.id) || null;
  });
  const videoRef = useRef<HTMLVideoElement>(null);
  const fetchingVideoRef = useRef<boolean>(false);

  const hasVideo = Boolean(
    (prop.videoUrl && typeof prop.videoUrl === 'string' && prop.videoUrl.trim()) ||
    (typeof prop._hasVideo === 'boolean' && prop._hasVideo)
  );
  // Cloudinary-optimized delivery URL for the current cover image
  const coverDeliveryUrl = buildCloudinaryUrl(prop.images[currentImgIdx] || prop.images[0], 'DISCOVERY_CARD');
  const mediaCount = prop._mediaCount ?? prop.images.length;
  const mediaLabel = hasVideo ? `${mediaCount} photos & videos` : `${mediaCount} ${mediaCount === 1 ? 'photo' : 'photos'}`;
  const location = [prop.sector, prop.city].filter(Boolean).join(', ');
  const area = formatPropertyArea(prop.totalAreaSqFt);
  const bhk = formatBhkDisplay(prop.bhk);
  const furnishing = formatFurnishingDisplay(prop.furnishingStatus);
  const floor = formatFloorDisplay(prop.floor, prop.totalFloors);
  const preferredTenant = formatPreferredTenantDisplay(prop.preferredTenant);
  const price = prop.listingType === 'SALE' ? prop.askingPrice : prop.monthlyRent;

  // On desktop deliberate hover: resolve real property video URL and start preview
  const handleMouseEnter = () => {
    const canHover = typeof window !== 'undefined' && window.matchMedia('(hover: hover)').matches;
    if (!canHover || reduceMotion) return;

    setIsHovered(true);

    if (hasVideo && !videoFailed) {
      // 1 & 2. Use direct or cached real video URL if already available
      if (resolvedVideoUrl) return;

      // 3. Lazily resolve real property video via safe public property detail endpoint
      if (!fetchingVideoRef.current) {
        fetchingVideoRef.current = true;
        propertyService
          .getPublicProperty(prop.id)
          .then((detail) => {
            const legitimateUrl = detail.videoUrl && typeof detail.videoUrl === 'string' && detail.videoUrl.trim()
              ? detail.videoUrl.trim()
              : null;
            if (legitimateUrl) {
              videoUrlCache.set(prop.id, legitimateUrl);
              setResolvedVideoUrl(legitimateUrl);
            } else {
              // 4. No legitimate video exists for this property: remain on real cover image
              setVideoFailed(true);
            }
          })
          .catch(() => {
            // 4. Request fails: remain on real cover image
            setVideoFailed(true);
          })
          .finally(() => {
            fetchingVideoRef.current = false;
          });
      }
    }
  };

  const handleMouseLeave = () => {
    setIsHovered(false);
    if (videoRef.current) {
      videoRef.current.pause();
    }
    setIsVideoPlaying(false);
  };

  // Video playback enforcement
  useEffect(() => {
    const videoEl = videoRef.current;
    if (!videoEl) return;

    if (isHovered && resolvedVideoUrl && !videoFailed) {
      videoEl.muted = true;
      videoEl.currentTime = 0;
      const playPromise = videoEl.play();
      if (playPromise !== undefined) {
        playPromise
          .then(() => {
            setIsVideoPlaying(true);
          })
          .catch(() => {
            setIsVideoPlaying(false);
          });
      }
    } else {
      videoEl.pause();
      setIsVideoPlaying(false);
    }
  }, [isHovered, resolvedVideoUrl, videoFailed]);

  return (
    <motion.article
      id={`property-card-${prop.id}`}
      onClick={(event) => {
        if ((event.target as HTMLElement).closest('button, a, input, select')) return;
        onViewDetails(prop);
      }}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
      initial={reduceMotion ? false : { opacity: 0, y: 12 }}
      whileInView={{ opacity: 1, y: 0 }}
      whileHover={reduceMotion ? undefined : { y: -2 }}
      viewport={{ once: true, margin: '-40px' }}
      transition={{ duration: 0.3, ease: [0.16, 1, 0.3, 1] }}
      className="group flex min-w-0 w-full cursor-pointer flex-col md:max-w-[640px] md:mx-auto lg:max-w-none"
    >
      {/* MEDIA SURFACE: EDGE-TO-EDGE THUMBNAIL WITH OBJECT-COVER */}
      <div className="relative aspect-[16/10] overflow-hidden rounded-[24px] bg-slate-200/60 sm:aspect-[16/9]">
        {/* Cover Photo */}
        <AnimatePresence mode="wait" initial={false}>
          {prop.images.length > 0 ? (
            <motion.img
              key={`${prop.id}-${currentImgIdx}`}
              initial={reduceMotion ? false : { opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={reduceMotion ? undefined : { opacity: 0 }}
              transition={{ duration: 0.18 }}
              src={coverDeliveryUrl || prop.images[currentImgIdx] || prop.images[0]}
              alt={prop.title}
              loading={index === 0 ? 'eager' : 'lazy'}
              {...({ fetchPriority: index === 0 ? 'high' : 'low' } as any)}
              className={`absolute inset-0 h-full w-full object-cover object-center transition-transform duration-700 ease-out ${
                isHovered && !isVideoPlaying && !reduceMotion ? 'scale-[1.03]' : 'scale-100'
              }`}
            />
          ) : (
            <div className="flex h-full w-full items-center justify-center px-6 text-center text-sm font-medium text-slate-400">
              Property media will be shared when available.
            </div>
          )}
        </AnimatePresence>

        {/* Video Surface (Active during desktop hover preview) */}
        {hasVideo && resolvedVideoUrl && !videoFailed && (
          <video
            ref={videoRef}
            src={resolvedVideoUrl}
            muted
            loop
            playsInline
            preload="none"
            onPlaying={() => setIsVideoPlaying(true)}
            onError={() => {
              setVideoFailed(true);
              setIsVideoPlaying(false);
            }}
            className={`absolute inset-0 h-full w-full object-cover object-center transition-opacity duration-300 ${
              isVideoPlaying ? 'opacity-100' : 'opacity-0 pointer-events-none'
            }`}
          />
        )}

        {/* Top Controls: Media Count (left) — Bookmark (right) */}
        <div className="absolute inset-x-3 top-3 flex items-start justify-between gap-2 sm:inset-x-4 sm:top-4 z-20">
          {/* Left: Media count badge */}
          {mediaCount > 0 ? (
            <button
              type="button"
              onClick={(event) => {
                event.stopPropagation();
                if (onOpenMediaModal) onOpenMediaModal(prop, 'PHOTOS');
                else onViewDetails(prop);
              }}
              aria-label={`View ${mediaLabel} for ${prop.title}`}
              className="inline-flex min-h-[32px] min-w-0 items-center gap-1.5 rounded-full border border-white/15 bg-slate-900/40 px-2.5 text-[11px] font-semibold text-white/90 shadow-sm backdrop-blur-md transition-colors hover:bg-slate-900/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 motion-reduce:transition-none"
            >
              <Camera className="h-3.5 w-3.5 shrink-0 text-white/80" aria-hidden="true" />
              <span className="truncate">{isVideoPlaying ? 'Preview playing' : mediaLabel}</span>
            </button>
          ) : (
            <span />
          )}

          {/* Right: Bookmark */}
          <button
            type="button"
            aria-label={`Save ${prop.title}`}
            title="Save property"
            onClick={(event) => {
              event.stopPropagation();
              onSaveFavorite?.(prop);
            }}
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-white/15 bg-slate-900/40 text-white/95 shadow-sm backdrop-blur-md transition-colors hover:bg-slate-900/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 motion-reduce:transition-none"
          >
            <Bookmark className="h-4 w-4 text-white/90" aria-hidden="true" />
          </button>
        </div>

        {/* Carousel Navigation Arrows */}
        {prop.images.length > 1 && !isVideoPlaying && (
          <>
            <button
              type="button"
              aria-label="Previous property photo"
              onClick={(event) => onChangeImage(-1, event)}
              className="absolute left-2.5 top-1/2 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full border border-white/15 bg-slate-900/40 text-white/90 shadow-md backdrop-blur-md transition-all hover:bg-slate-900/65 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 sm:left-3.5 z-20"
            >
              <ChevronLeft className="h-5 w-5" aria-hidden="true" />
            </button>
            <button
              type="button"
              aria-label="Next property photo"
              onClick={(event) => onChangeImage(1, event)}
              className="absolute right-2.5 top-1/2 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full border border-white/15 bg-slate-900/40 text-white/90 shadow-md backdrop-blur-md transition-all hover:bg-slate-900/65 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 sm:right-3.5 z-20"
            >
              <ChevronRight className="h-5 w-5" aria-hidden="true" />
            </button>
          </>
        )}

        {/* Old bottom room tag removed — replaced by top-left premium chip above */}
      </div>

      {/* OVERLAPPING FLOATING INFORMATION PANEL */}
      <div className="relative z-10 mx-3 -mt-7 flex min-w-0 flex-1 flex-col rounded-[22px] border border-slate-200/90 bg-white p-3.5 shadow-[0_12px_28px_-12px_rgba(15,23,42,0.12),0_4px_12px_-4px_rgba(15,23,42,0.06)] transition-[box-shadow,border-color] duration-200 group-hover:border-slate-300 group-hover:shadow-[0_20px_35px_-12px_rgba(15,23,42,0.18),0_6px_14px_-4px_rgba(15,23,42,0.08)] group-focus-within:ring-2 group-focus-within:ring-emerald-500 motion-reduce:transition-none sm:mx-4 sm:-mt-9 sm:px-4.5 sm:pt-3.5 sm:pb-4">
        <div className="flex min-w-0 items-center justify-between gap-3 text-xs font-semibold text-slate-600">
          {location && (
            <span className="flex min-w-0 items-center gap-1.5" title={location}>
              <MapPin className="h-4 w-4 shrink-0 text-emerald-700" aria-hidden="true" />
              <span className="truncate">{location}</span>
            </span>
          )}
          {area !== null && <span className="shrink-0 tabular-nums text-slate-500">{area}</span>}
        </div>

        <h3 className="mt-1.5 line-clamp-2 break-words font-['Outfit',sans-serif] text-lg font-bold leading-snug text-slate-950 sm:min-h-[2.85rem] sm:text-xl" title={prop.title}>
          {prop.title}
        </h3>

        {(bhk || furnishing || floor) && (
          <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-600">
            {bhk && (
              <span className="inline-flex items-center gap-1.5 font-bold text-slate-800">
                <BedDouble className="h-4 w-4 shrink-0 text-slate-500" aria-hidden="true" />
                {bhk}
              </span>
            )}
            {furnishing && <span>{furnishing}</span>}
            {floor && <span>{floor}</span>}
          </div>
        )}

        {preferredTenant && (
          <p className="mt-1 truncate text-xs text-slate-500" title={preferredTenant}>
            {preferredTenant}
          </p>
        )}

        <div className="mt-auto pt-3">
          <div className="grid grid-cols-1 gap-2 border-t border-slate-200/90 pt-2.5 min-[360px]:grid-cols-[minmax(0,1fr)_auto] min-[360px]:items-end min-[360px]:gap-3">
            <div className="min-w-0">
              <p className="text-[10px] font-bold uppercase tracking-[0.1em] text-slate-500">
                {prop.listingType === 'SALE' ? 'Asking price' : 'Monthly rent'}
              </p>
              <p className="mt-0.5 break-words font-['Outfit',sans-serif] text-[clamp(1.3rem,2vw,1.65rem)] font-extrabold leading-tight tabular-nums text-slate-950">
                {typeof price === 'number' && price > 0 ? `₹${price.toLocaleString('en-IN')}` : 'On request'}
                {prop.listingType !== 'SALE' && typeof price === 'number' && price > 0 && (
                  <span className="ml-1 text-xs font-medium text-slate-500">/month</span>
                )}
              </p>
            </div>
            {prop.listingType !== 'SALE' && (
              <div className="min-w-0 min-[360px]:text-right">
                <p className="text-[10px] font-bold uppercase tracking-[0.1em] text-slate-500">Deposit</p>
                <p className="mt-0.5 break-words text-sm font-semibold leading-snug tabular-nums text-slate-700">
                  {formatSecurityDeposit(prop.securityDeposit)}
                </p>
              </div>
            )}
          </div>

          <div className="mt-2.5 flex min-w-0 items-center justify-between gap-2">
            <p className="min-w-0 text-[11px] leading-4 text-slate-500">
              Contact details protected<span className="hidden sm:inline"> · Availability checked before visit</span>
            </p>
            <button
              type="button"
              onClick={(event) => {
                event.stopPropagation();
                onViewDetails(prop);
              }}
              aria-label={`View details for ${prop.title}`}
              className="inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-xl px-2.5 text-xs font-bold text-emerald-800 transition-colors hover:bg-emerald-50 hover:text-emerald-900 active:bg-emerald-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 motion-reduce:transition-none"
            >
              View details <ArrowRight className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>
        </div>
      </div>
    </motion.article>
  );
};

export const PropertyShowcase: React.FC<PropertyShowcaseProps> = ({
  properties,
  onViewDetails,
  onRefineSearch,
  onOpenMediaModal,
  selectedSectorFilter,
  selectedCityFilter,
  onSaveFavorite,
  isLoading = false,
  error = null,
  onRetry,
  onClearLocality
}) => {
  const [activeImageIndex, setActiveImageIndex] = useState<Record<number, number>>({});
  const reduceMotion = useReducedMotion();

  const changeImage = (propId: number, maxImages: number, direction: -1 | 1, event: React.MouseEvent) => {
    event.stopPropagation();
    setActiveImageIndex((prev) => ({
      ...prev,
      [propId]: ((prev[propId] || 0) + direction + maxImages) % maxImages
    }));
  };

  return (
    <section className="relative overflow-hidden border-b border-slate-200/80 bg-slate-50/80 py-10 font-['Inter',sans-serif] sm:py-12">
      <div className="relative z-10 mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        {/* Results context header and location refinement actions */}
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
          className="mb-8 flex flex-col gap-4 border-b border-slate-200 pb-5 sm:flex-row sm:items-end sm:justify-between w-full md:max-w-[640px] md:mx-auto lg:max-w-none"
        >
          <div className="min-w-0">
            <p className="text-xs font-bold uppercase tracking-[0.14em] text-emerald-700">Rental homes</p>
            <h2 id="discovery-results-heading" tabIndex={-1} className="mt-2 break-words font-['Outfit',sans-serif] text-3xl font-extrabold tracking-tight text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 sm:text-4xl">
              Homes in <span className="text-emerald-700">{selectedSectorFilter || selectedCityFilter}</span>
            </h2>
            <p className="mt-1 text-sm text-slate-600">
              {isLoading
                ? `Loading homes in ${selectedSectorFilter || selectedCityFilter}…`
                : error
                  ? `Unable to load homes in ${selectedSectorFilter || selectedCityFilter}`
                  : `${selectedSectorFilter ? `${selectedCityFilter} · ` : ''}Showing ${properties.length} ${properties.length === 1 ? 'home' : 'homes'}`}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {selectedSectorFilter && !isLoading && !error && onClearLocality && (
              <button type="button" onClick={onClearLocality} className="inline-flex min-h-11 items-center justify-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3.5 text-xs font-semibold text-slate-700 shadow-xs transition-colors duration-150 hover:bg-slate-50 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">
                Clear locality
              </button>
            )}
            <button type="button" onClick={onRefineSearch} className="inline-flex min-h-11 w-fit items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-800 shadow-sm transition-colors duration-150 hover:border-emerald-500 hover:text-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">
              <MapPin className="h-4 w-4 text-emerald-700" aria-hidden="true" />
              Change search
            </button>
          </div>
        </motion.div>

        {isLoading ? (
          <div role="status" aria-label="Loading available properties" aria-busy="true">
            <span className="sr-only">Loading available properties…</span>
            <div className="grid grid-cols-1 gap-5 lg:grid-cols-2 lg:gap-6">
              {Array.from({ length: 6 }, (_, index) => <SkeletonPropertyCard key={index} />)}
            </div>
          </div>
        ) : error ? (
          <div className="rounded-3xl border border-rose-200 bg-rose-50/70 p-6 text-center sm:p-8" role="alert">
            <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-2xl bg-rose-100 text-rose-700">
              <CircleAlert className="h-6 w-6" aria-hidden="true" />
            </div>
            <h3 className="font-['Outfit',sans-serif] text-lg font-bold text-slate-900">Unable to load properties</h3>
            <p className="mx-auto mt-1 max-w-md text-sm text-slate-600">{error}</p>
            <div className="mt-5 flex flex-wrap justify-center gap-3">
              {onRetry && (
                <button type="button" onClick={onRetry} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-slate-900 px-5 text-sm font-bold text-white transition-colors hover:bg-slate-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">Try again</button>
              )}
              <button type="button" onClick={onRefineSearch} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-5 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">
                <MapPin className="h-4 w-4 text-emerald-700" aria-hidden="true" />
                Change search
              </button>
            </div>
          </div>
        ) : properties.length > 0 ? (
          <div className="grid grid-cols-1 gap-5 lg:grid-cols-2 lg:gap-6">
            {properties.map((prop, index) => {
              const currentImgIdx = Math.min(activeImageIndex[prop.id] || 0, Math.max(prop.images.length - 1, 0));
              return (
                <DiscoveryPropertyCard
                  key={prop.id}
                  prop={prop}
                  index={index}
                  reduceMotion={reduceMotion}
                  onViewDetails={onViewDetails}
                  onOpenMediaModal={onOpenMediaModal}
                  onSaveFavorite={onSaveFavorite}
                  currentImgIdx={currentImgIdx}
                  onChangeImage={(dir, e) => changeImage(prop.id, prop.images.length, dir, e)}
                />
              );
            })}
          </div>
        ) : (
          <div className="rounded-3xl border border-slate-200/90 bg-white p-8 text-center shadow-xs sm:p-12">
            <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-700">
              <MapPin className="h-6 w-6 text-emerald-700" aria-hidden="true" />
            </div>
            <h3 className="font-['Outfit',sans-serif] text-xl font-extrabold text-slate-900">No homes found</h3>
            <p className="mx-auto mt-1.5 max-w-md text-sm text-slate-600">Try another location or adjust your filters.</p>
            <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
              {selectedSectorFilter && onClearLocality && (
                <button type="button" onClick={onClearLocality} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-5 text-sm font-bold text-white transition-colors hover:bg-emerald-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">Clear locality</button>
              )}
              <button type="button" onClick={onRefineSearch} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-5 text-sm font-semibold text-slate-800 transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">
                <MapPin className="h-4 w-4 text-emerald-700" aria-hidden="true" />
                Change search
              </button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
};
