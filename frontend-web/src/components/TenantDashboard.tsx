import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight, BedDouble, Building2, CalendarDays, ChevronRight, Clock3, Heart, Home as HomeIcon, LoaderCircle, LogOut, MapPin, RefreshCw, Search, ShieldCheck, SlidersHorizontal, Sparkles, X } from 'lucide-react';
import { motion, useReducedMotion } from 'framer-motion';
import { Property, UserProfile } from '../types';
import { tenantVisitService, TenantVisitRequest } from '../services/tenantVisitService';
import { createVisitOperationId, TenantVisitOutcome, TenantVisitStartCode, VisitExecutionView, visitExecutionService } from '../services/visitExecutionService';
import { secondsUntilVisitCodeTime } from '../utils/visitExecutionPresentation';
import { belongsToTenantVisitSession, isCurrentTenantVisitSession, readTenantVisitSession } from '../utils/tenantVisitSession';
import { appendUniqueVisitRequests, tenantRequestStatusForProperty, tenantVisitCtaLabel, tenantVisitStatusLabel, tenantVisitSummary, tenantVisitView } from '../utils/tenantVisitView';
import { buildCloudinaryUrl } from '../utils/mediaTransform';
import { resetFiltersForManualCityChange, resetFiltersForSearchClear, RentalPropertyType, RentalSearchFilters, discoverySearchKey } from '../utils/rentalSearch';
import { queueTenantHeroFilterScroll, resolveTenantHeroFilterScroll, type PendingTenantHeroFilterScroll } from '../utils/tenantQuickFilterScroll';
import { CompactSearchContext } from './CompactSearchContext';
import { favoriteService } from '../services/favoriteService';
import { propertyService } from '../services/propertyService';
import { exitQuickViewFullscreen, getQuickViewMedia, isQuickViewFullscreenActive, lockQuickViewBodyScroll, moveQuickViewMediaIndex, nextQuickViewControlsState, quickViewSwipeStartsOnControl, resolveQuickViewStageAction, type QuickViewFullscreenMode } from '../utils/quickViewMedia';
import { notifyTenantFavoriteChanged } from '../utils/tenantFavorites';
import { applyPersistedSavedHomeChange, mergeSavedHomes } from '../utils/tenantSavedHomes';
import { formatPropertyArea } from '../utils/discoveryCardData';
import { formatExactPropertyTimestampIST, formatPropertyRelativeTime, parsePropertyTimestamp } from '../utils/propertyTimestamp';
import { tenantPropertyTypeLabel } from '../utils/tenantPropertyTypeLabel';
import { TenantQuickRefineMobile } from './TenantQuickRefine';
import { TenantNavigationRail } from './TenantNavigationRail';
import { TenantMobileDock } from './TenantMobileDock';
import { TenantDiscoveryCardMedia } from './TenantDiscoveryCardMedia';
import { QuickViewVideoPlayer } from './QuickViewVideoPlayer';
import { quickRefineRentBounds as getQuickRefineRentBounds } from '../utils/tenantQuickRefine';
import { QUICK_REFINE_PROPERTY_TYPES } from '../utils/tenantQuickRefine';
import type { QuickRefineRentBounds } from '../utils/tenantQuickRefine';
import { shouldRenderTenantMobileDock, tenantMobileDockBadges } from '../utils/tenantMobileDock';
import type { TenantMobileDockItem } from '../utils/tenantMobileDock';
import { tenantOutcomeSummaryLabel, tenantPropertyOutcomeLabel, tenantVisitOutcomeStatusLabel, tenantVisitOutcomeSummaryText } from '../utils/tenantVisitOutcomePresentation';
import {
  applyFavoriteLookupFailure,
  applyFavoriteLookupSuccess,
  applyFavoriteMutation,
  isFavoriteLookupReadyFor,
  unresolvedFavoriteIds,
  type FavoriteLookupState
} from '../utils/favoriteLookup';

interface TenantDashboardProps {
  user: UserProfile;
  savedCount: number | null;
  properties: Property[];
  discoveryState: 'LOADING' | 'READY' | 'ERROR';
  discoveryLoadedKey: string | null;
  discoveryCity: string;
  discoveryQuery: string;
  searchFilters: RentalSearchFilters;
  hasMoreProperties: boolean;
  loadingMoreProperties: boolean;
  loadMorePropertiesError: string | null;
  onSearchHomes: (filters: RentalSearchFilters) => void;
  onRetryDiscovery: () => void;
  onLoadMoreProperties: () => void;
  onRequestVisit: (property: Property) => void;
  onViewProperty: (property: Property) => void;
  hasLessorCapability: boolean | null | 'error';
  onOpenLessor: () => void;
  onLogout: () => void;
}

type HistoryState = {
  identityKey: string | null;
  status: 'loading' | 'ready' | 'error';
  requests: TenantVisitRequest[];
  totalCount: number;
  page: number;
  hasMore: boolean;
};

const emptyHistory: HistoryState = {
  identityKey: null, status: 'loading', requests: [], totalCount: 0, page: 0, hasMore: false
};

type TenantSessionHistory = { identityKey: string | null; status: 'loading' | 'ready' | 'error'; sessions: VisitExecutionView[]; totalPages: number };
const emptyTenantSessions: TenantSessionHistory = { identityKey: null, status: 'loading', sessions: [], totalPages: 0 };
type TenantOutcomeHistory = { identityKey: string | null; status: 'loading' | 'ready' | 'error'; sessions: TenantVisitOutcome[] };
const emptyTenantOutcomeHistory: TenantOutcomeHistory = { identityKey: null, status: 'loading', sessions: [] };

const HERO_BHK_FILTERS = ['2 BHK', '3 BHK'] as const;
const HERO_PROPERTY_FILTERS: { label: string; value: RentalPropertyType }[] = [
  { label: 'Flat', value: 'FLAT' },
  { label: 'House', value: 'HOUSE' },
  { label: 'Penthouse', value: 'PENTHOUSE' }
];

const tenantCardRevealVariants = {
  hidden: { opacity: 0, y: 28, scale: 0.985 },
  visible: { opacity: 1, y: 0, scale: 1 }
};

const tenantCardImageRevealVariants = {
  hidden: { scale: 1.02 },
  visible: { scale: 1 }
};

const formatRequestedDate = (value: string | null | undefined): string | null => {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null :
    new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }).format(date);
};

const formatVisitTime = (value: string | null | undefined, zoneId?: string | null): string => {
  if (!value) return 'Time unavailable';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Time unavailable'
    : `${new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', ...(zoneId ? { timeZone: zoneId } : {}) }).format(date)}${zoneId ? ` · ${zoneId}` : ''}`;
};

const focusClass = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700';

const TenantVisitRequestCard: React.FC<{ request: TenantVisitRequest }> = ({ request }) => {
  const [imageFailed, setImageFailed] = useState(false);
  const date = formatRequestedDate(request.requestedAt);
  const location = [readText(request.sector), readText(request.city)].filter(Boolean).join(', ');
  const title = readText(request.propertyTitle);
  const bhk = readText(request.bhk);
  const propertyType = readText(request.propertyType);
  const preferredTiming = readText(request.preferredVisitTiming);
  const awaiting = request.status === 'RECEIVED' || request.status === 'COORDINATING';
  return <article className="tenant-v0-request-card min-w-0">
    <div className="tenant-v0-request-image">
      {request.coverImageUrl && !imageFailed
        ? <img src={buildCloudinaryUrl(request.coverImageUrl, 'DISCOVERY_CARD')} alt="" loading="lazy" onError={() => setImageFailed(true)} />
        : <span role="img" aria-label="Property image unavailable"><HomeIcon size={28} aria-hidden="true" /></span>}
    </div>
    <div className="tenant-v0-request-info min-w-0">
      <span className="tenant-v0-eyebrow">VISIT REQUEST</span>
      <span className="tenant-v0-request-status-mobile">{tenantVisitStatusLabel(request.status)}</span>
      <h3>{title || 'Property'}</h3>
      {location && <p className="tenant-v0-request-location"><MapPin size={14} aria-hidden="true" />{location}</p>}
      {(bhk || propertyType) && <p className="tenant-v0-request-facts">{[bhk, propertyType?.replace(/_/g, ' ').toLowerCase()].filter(Boolean).join(' · ')}</p>}
      {(date || preferredTiming) && <p className="tenant-v0-request-timing"><CalendarDays size={15} aria-hidden="true" />{date ? `Requested ${date}` : 'Timing requested'}{preferredTiming ? ` · Preferred: ${preferredTiming}` : ''}</p>}
      {awaiting && <p className="tenant-v0-request-awaiting"><span aria-hidden="true" />Not a confirmed appointment</p>}
      {request.propertyAvailable && Number.isSafeInteger(request.propertyId) && request.propertyId > 0
        ? <Link to={`/property/${request.propertyId}`} className={`tenant-v0-request-link ${focusClass}`}>View property <ArrowRight size={14} aria-hidden="true" /></Link>
        : <p className="tenant-v0-request-unavailable">This property is no longer available to view.</p>}
    </div>
    <div className="tenant-v0-request-aside"><span>{tenantVisitStatusLabel(request.status)}</span></div>
  </article>;
};
const readText = (value: unknown): string => typeof value === 'string' ? value.trim() : '';
const TENANT_CARD_BHK_PATTERN = /^(\d+)\s*(bhk|rk)$/i;
const TENANT_CARD_FURNISHING_LABELS: Record<string, string> = {
  'fully furnished': 'Fully furnished',
  'semi furnished': 'Semi-furnished',
  furnished: 'Furnished',
  unfurnished: 'Unfurnished'
};

const formatTenantCardBhk = (value: string | null | undefined): string | null => {
  const clean = value?.trim();
  if (!clean || clean === '0' || clean.toLowerCase() === '0bhk') return null;
  const match = TENANT_CARD_BHK_PATTERN.exec(clean);
  return match ? `${match[1]} ${match[2].toUpperCase()}` : clean;
};

const formatTenantCardFurnishing = (value: string | null | undefined): string | null => {
  const normalized = value?.trim().replace(/[_-]/g, ' ').replace(/\s+/g, ' ').toLowerCase();
  return normalized ? TENANT_CARD_FURNISHING_LABELS[normalized] ?? null : null;
};

const SupportingPropertyCard: React.FC<{
  property: Property;
  visitRequestStatus: string | null;
  onRequestVisit: (property: Property) => void;
  onOpenQuickView: (property: Property) => void;
  isFavorite: boolean;
  favoriteStateReady: boolean;
  favoritePending: boolean;
  onToggleFavorite: (property: Property) => void;
  revealIndex: number;
  reduceMotion: boolean;
}> = ({ property, visitRequestStatus, onRequestVisit, onOpenQuickView, isFavorite, favoriteStateReady, favoritePending, onToggleFavorite, revealIndex, reduceMotion }) => {
  const isRent = property.listingType === 'RENT' && property.monthlyRent > 0;
  const isSale = property.listingType === 'SALE' && Number.isFinite(property.askingPrice) && (property.askingPrice ?? 0) > 0;
  const amount = isRent ? property.monthlyRent : isSale ? property.askingPrice! : null;
  const location = [property.sector, property.city].filter((part): part is string => Boolean(part?.trim())).join(' · ');
  const facts = [formatTenantCardBhk(property.bhk),
    property.bathroomCount ? `${property.bathroomCount} bath` : null,
    formatPropertyArea(property.totalAreaSqFt),
    formatTenantCardFurnishing(property.furnishingStatus)].filter((fact): fact is string => Boolean(fact));
  const propertyType = tenantPropertyTypeLabel(property.propertyType);
  const parsedUpdatedDate = parsePropertyTimestamp(property.updatedAt);
  const relativeUpdated = formatPropertyRelativeTime(parsedUpdatedDate);
  const exactUpdatedIST = formatExactPropertyTimestampIST(parsedUpdatedDate);
  return <motion.article initial={reduceMotion || typeof IntersectionObserver === 'undefined' ? false : 'hidden'}
    whileInView="visible" whileHover={reduceMotion ? undefined : { y: -2 }} variants={tenantCardRevealVariants}
    viewport={{ once: true, amount: 0.12 }}
    transition={{ duration: reduceMotion ? 0 : 0.4, delay: reduceMotion ? 0 : (revealIndex % 3) * 0.06, ease: [0.16, 1, 0.3, 1] }}
    className="tenant-v0-card group/available-card relative flex h-full min-w-0 flex-col overflow-hidden border border-[#eae8e2] bg-white">
    <div className="relative aspect-[1.48] min-w-0 overflow-hidden bg-[#ebe9e2]">
      <motion.div variants={tenantCardImageRevealVariants} className="absolute inset-0">
        <TenantDiscoveryCardMedia property={property} reduceMotion={reduceMotion} onOpen={() => onOpenQuickView(property)} openerId={`tenant-property-${property.id}`} />
      </motion.div>
      {propertyType && <span className="tenant-v0-photo-tag"><span aria-hidden="true" />{propertyType}</span>}
      <button type="button" onClick={() => onToggleFavorite(property)} disabled={!favoriteStateReady || favoritePending}
        aria-label={!favoriteStateReady ? `Saved state loading for ${property.title}` : favoritePending ? `${isFavorite ? 'Removing' : 'Saving'} ${property.title}` : isFavorite ? `Remove ${property.title} from saved homes` : `Save ${property.title}`}
        aria-pressed={favoriteStateReady ? isFavorite : undefined}
        className={`tenant-v0-save-button ${isFavorite ? 'is-saved' : ''} ${focusClass}`}>
        {favoritePending ? <LoaderCircle size={17} className="motion-safe:animate-spin" aria-hidden="true" /> : <Heart size={18} fill={isFavorite ? 'currentColor' : 'none'} aria-hidden="true" />}
      </button>
    </div>
    <div className="tenant-v0-card-body flex flex-1 flex-col">
      {location && <p className="tenant-v0-card-location"><MapPin size={13} aria-hidden="true" />{location}</p>}
      <h3 className="tenant-v0-card-title"><button type="button" onClick={() => onOpenQuickView(property)}>{property.title?.trim() || 'Property'}</button></h3>
      {facts.length > 0 && <p className="tenant-v0-card-specs">{facts.map((fact, index) => <React.Fragment key={`${fact}-${index}`}>{index > 0 && <span aria-hidden="true">·</span>}<span>{fact}</span></React.Fragment>)}</p>}
      {relativeUpdated && parsedUpdatedDate && <time className="tenant-v0-card-updated" dateTime={parsedUpdatedDate.toISOString()} title={exactUpdatedIST ? `Updated ${exactUpdatedIST}` : undefined}><Clock3 size={12} aria-hidden="true" /><span>Updated {relativeUpdated}</span></time>}
      <div className="tenant-v0-card-bottom">
        <p><strong>{amount !== null ? `₹${amount.toLocaleString('en-IN')}` : 'Price on request'}</strong>{isRent && amount !== null && <span> / month</span>}</p>
        <button type="button" onClick={() => onOpenQuickView(property)} aria-label={`Quick view ${property.title || 'property'}`} className="tenant-v0-card-arrow"><ArrowRight size={17} aria-hidden="true" /></button>
      </div>
      <button type="button" onClick={() => onRequestVisit(property)} className="tenant-v0-card-visit">{tenantVisitCtaLabel(visitRequestStatus) ?? 'Request a visit'} <ArrowRight size={14} aria-hidden="true" /></button>
    </div>
  </motion.article>;
};

const SavedHomeCard: React.FC<{
  property: Property;
  visitRequestStatus: string | null;
  isFavorite: boolean;
  favoriteStateReady: boolean;
  favoritePending: boolean;
  onToggleFavorite: (property: Property) => void;
  onOpenQuickView: (property: Property) => void;
  onRequestVisit: (property: Property) => void;
}> = props => {
  const reduceMotion = useReducedMotion() === true;
  return <SupportingPropertyCard {...props} revealIndex={0} reduceMotion={reduceMotion} />;
};

const TenantPropertyQuickView: React.FC<{
  propertyId: number;
  visitRequestStatus: (propertyId: number) => string | null;
  onClose: () => void;
  onRequestVisit: (property: Property) => void;
  onViewProperty: (property: Property) => void;
  isFavorite: boolean;
  favoriteStateReady: boolean;
  favoritePending: boolean;
  onToggleFavorite: (property: Property) => void;
}> = ({ propertyId, visitRequestStatus, onClose, onRequestVisit, onViewProperty,
  isFavorite, favoriteStateReady, favoritePending, onToggleFavorite }) => {
  const [property, setProperty] = useState<Property | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [failedMedia, setFailedMedia] = useState<Set<string>>(() => new Set());
  const [activeMediaIndexState, setActiveMediaIndex] = useState(0);
  const [videoControls, setVideoControls] = useState({ playing: false, visible: true, activityVersion: 0 });
  const [playbackError, setPlaybackError] = useState<string | null>(null);
  const [fullscreenMode, setFullscreenMode] = useState<QuickViewFullscreenMode>('NONE');
  const dialogRef = useRef<HTMLElement>(null);
  const mediaStageRef = useRef<HTMLDivElement>(null);
  const activeVideoRef = useRef<HTMLVideoElement>(null);
  const mediaGestureRef = useRef<{ pointerId: number; x: number; y: number; time: number; mediaIndex: number; eligible: boolean } | null>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const focusRestoreTimerRef = useRef<number | null>(null);
  const onCloseRef = useRef(onClose);
  const media = useMemo(() => getQuickViewMedia(property), [property]);
  const changeMedia = useCallback((direction: -1 | 1, count: number) => {
    activeVideoRef.current?.pause();
    setPlaybackError(null);
    setVideoControls(previous => nextQuickViewControlsState(previous, 'MEDIA_CHANGE'));
    setActiveMediaIndex(index => moveQuickViewMediaIndex(index, count, direction));
  }, []);
  const toggleActiveVideo = useCallback(() => {
    const video = activeVideoRef.current;
    if (!video) return;
    setPlaybackError(null);
    if (video.paused) void video.play().catch(() => {
      if (activeVideoRef.current === video) setPlaybackError('Video could not start. Try again.');
    });
    else video.pause();
  }, []);
  const revealControls = useCallback(() => {
    setVideoControls(previous => nextQuickViewControlsState(previous, 'ACTIVITY'));
  }, []);

  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);

  useEffect(() => {
    if (fullscreenMode !== 'APP_FALLBACK') return;
    return lockQuickViewBodyScroll(document.body);
  }, [fullscreenMode]);

  useEffect(() => {
    if (!videoControls.playing) return;
    const scheduledVersion = videoControls.activityVersion;
    const timer = window.setTimeout(() => {
      setVideoControls(previous => previous.activityVersion === scheduledVersion
        ? nextQuickViewControlsState(previous, 'TIMEOUT') : previous);
    }, 1800);
    return () => window.clearTimeout(timer);
  }, [videoControls.playing, videoControls.activityVersion]);

  useEffect(() => {
    const controller = new AbortController();
    activeVideoRef.current?.pause();
    setFullscreenMode('NONE');
    setPlaybackError(null);
    setVideoControls(previous => nextQuickViewControlsState(previous, 'MEDIA_CHANGE'));
    setLoading(true);
    setError(null);
    setProperty(null);
    setActiveMediaIndex(0);
    setFailedMedia(new Set());
    propertyService.getPublicProperty(propertyId, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return;
        setProperty(result);
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setError(requestError instanceof Error ? requestError.message : 'Unable to load this property. Please try again.');
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [propertyId, retry]);

  useEffect(() => {
    if (focusRestoreTimerRef.current !== null) {
      window.clearTimeout(focusRestoreTimerRef.current);
      focusRestoreTimerRef.current = null;
    }
    const previousOverflow = document.body.style.overflow;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    document.body.style.overflow = 'hidden';
    const focusTimer = window.setTimeout(() => {
      if (document.activeElement !== mediaStageRef.current) closeButtonRef.current?.focus();
    }, 0);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        const iosVideo = activeVideoRef.current as (HTMLVideoElement & { webkitDisplayingFullscreen?: boolean }) | null;
        if (document.fullscreenElement === mediaStageRef.current || iosVideo?.webkitDisplayingFullscreen) return;
        if (mediaStageRef.current?.classList.contains('is-app-fullscreen')) {
          event.preventDefault();
          setFullscreenMode('NONE');
          return;
        }
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (!dialogRef.current) return;
      if ((event.key === ' ' || event.code === 'Space') && !event.repeat
        && document.activeElement === mediaStageRef.current && activeVideoRef.current) {
        event.preventDefault();
        revealControls();
        toggleActiveVideo();
        return;
      }
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        const target = event.target instanceof Element ? event.target : null;
        if (target?.closest('input, select, textarea, [contenteditable="true"], [role="slider"], [role="textbox"]')) return;
        if (media.length <= 1) return;
        event.preventDefault();
        changeMedia(event.key === 'ArrowRight' ? 1 : -1, media.length);
        return;
      }
      if (event.key !== 'Tab') return;
      const focusScope = mediaStageRef.current?.classList.contains('is-app-fullscreen') ? mediaStageRef.current : dialogRef.current;
      const controls = [...focusScope.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
      )].filter(control => control.offsetParent !== null);
      if (controls.length === 0) { event.preventDefault(); dialogRef.current.focus(); return; }
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current
        || document.activeElement === mediaStageRef.current)) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      window.clearTimeout(focusTimer);
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      focusRestoreTimerRef.current = window.setTimeout(() => {
        if (previousFocus?.isConnected) previousFocus.focus();
        focusRestoreTimerRef.current = null;
      }, 0);
    };
  }, [media.length, changeMedia, revealControls, toggleActiveVideo]);

  const activeMediaIndex = media.length ? ((activeMediaIndexState % media.length) + media.length) % media.length : 0;
  const activeMedia = media[activeMediaIndex] ?? null;
  useEffect(() => {
    if (activeMedia?.type === 'VIDEO' && window.matchMedia('(pointer: fine)').matches) {
      mediaStageRef.current?.focus({ preventScroll: true });
    }
  }, [activeMedia?.type, activeMedia?.url]);
  const handleMediaPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    revealControls();
    if (!event.isPrimary || (event.pointerType === 'mouse' && event.button !== 0)) {
      mediaGestureRef.current = null;
      return;
    }
    const target = event.target instanceof Element ? event.target : null;
    if (activeMedia?.type === 'VIDEO' && event.pointerType === 'mouse' && !quickViewSwipeStartsOnControl(target)) {
      event.currentTarget.focus({ preventScroll: true });
    }
    mediaGestureRef.current = {
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      time: event.timeStamp,
      mediaIndex: activeMediaIndex,
      eligible: !quickViewSwipeStartsOnControl(target)
    };
  };
  const handleMediaPointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
    const start = mediaGestureRef.current;
    mediaGestureRef.current = null;
    if (!start?.eligible || start.pointerId !== event.pointerId || start.mediaIndex !== activeMediaIndex || !activeMedia) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const isFullscreen = isQuickViewFullscreenActive(fullscreenMode, mediaStageRef.current);
    const action = resolveQuickViewStageAction({
      startX: start.x, startY: start.y, endX: event.clientX, endY: event.clientY,
      durationMs: Math.max(0, event.timeStamp - start.time), stageLeft: bounds.left,
      stageWidth: bounds.width, mediaType: activeMedia.type, mediaCount: media.length,
      fullscreen: isFullscreen
    });
    if (action === 'PREVIOUS' || action === 'NEXT') changeMedia(action === 'NEXT' ? 1 : -1, media.length);
    else if (action === 'TOGGLE_PLAYBACK') toggleActiveVideo();
    else if (action === 'EXIT_FULLSCREEN') {
      exitQuickViewFullscreen({
        fullscreenMode,
        stageElement: mediaStageRef.current,
        videoElement: activeVideoRef.current,
        onFullscreenModeChange: setFullscreenMode,
        onActivity: revealControls
      });
    }
  };
  const formatMoney = (value?: number | null) => typeof value === 'number' && value > 0 ? `₹${value.toLocaleString('en-IN')}` : null;
  const typeLabel = property?.propertyType?.replace(/_/g, ' ').toLowerCase();
  const price = property?.listingType === 'RENT' ? formatMoney(property.monthlyRent) : formatMoney(property?.askingPrice);
  const facts = property ? [
    { label: 'Bedrooms', value: formatTenantCardBhk(property.bhk) },
    { label: 'Bathrooms', value: property.bathroomCount && property.bathroomCount > 0 ? `${property.bathroomCount} ${property.bathroomCount === 1 ? 'bath' : 'baths'}` : null },
    { label: 'Area', value: Number.isFinite(property.totalAreaSqFt) && property.totalAreaSqFt > 0 ? `${property.totalAreaSqFt.toLocaleString('en-IN')} sq ft` : null },
    { label: 'Home type', value: typeLabel ? typeLabel.replace(/^\w/, value => value.toUpperCase()) : null },
    { label: 'Furnishing', value: formatTenantCardFurnishing(property.furnishingStatus) }
  ].filter((fact): fact is { label: string; value: string } => Boolean(fact.value)) : [];
  const description = property?.description?.trim();
  const locality = property ? [property.sector, property.city].map(readText).filter(Boolean).join(', ') : '';

  return (
    <div className={`tenant-v0-quick-backdrop fixed inset-0 z-[110] grid place-items-center overflow-y-auto p-3 sm:p-[25px] ${fullscreenMode === 'APP_FALLBACK' ? 'has-app-fullscreen' : ''}`} data-testid="tenant-property-quick-view"
      onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
      <section ref={dialogRef} role="dialog" aria-modal="true"
        aria-labelledby={property && !loading && !error ? 'tenant-quick-view-property-title' : 'tenant-quick-view-dialog-title'} tabIndex={-1}
        className={`tenant-v0-quick-modal min-w-0 overflow-hidden ${loading || error || !property ? 'is-pending' : ''} ${fullscreenMode === 'APP_FALLBACK' ? 'has-app-fullscreen' : ''}`}>
        <span id="tenant-quick-view-dialog-title" className="sr-only">Property quick view</span>
        {loading && <div role="status" aria-label="Loading property details" className="tenant-v0-quick-loading"><div /><span /><span /><span /></div>}
        {!loading && error && <div role="alert" className="tenant-v0-quick-error"><p>{error}</p><button type="button" onClick={() => setRetry(value => value + 1)} className={focusClass}>Try again</button></div>}
        {!loading && !error && property && <>
          <div ref={mediaStageRef} tabIndex={-1}
            className={`tenant-v0-quick-media ${activeMedia?.type === 'VIDEO' ? 'is-video' : ''} ${fullscreenMode === 'APP_FALLBACK' ? 'is-app-fullscreen' : ''} ${activeMedia?.type === 'VIDEO' && videoControls.playing && !videoControls.visible ? 'is-controls-hidden' : ''}`}
            onPointerDown={handleMediaPointerDown} onPointerUp={handleMediaPointerUp}
            onPointerCancel={() => { mediaGestureRef.current = null; }}
            onPointerMove={event => { if (event.pointerType === 'mouse' && (event.movementX || event.movementY)) revealControls(); }}
            onFocusCapture={revealControls}>
            {activeMedia && !failedMedia.has(activeMedia.url)
              ? activeMedia.type === 'VIDEO'
                ? <QuickViewVideoPlayer key={`${activeMedia.url}-${activeMediaIndex}`} src={activeMedia.url}
                    label={activeMedia.tagLabel ? `${activeMedia.tagLabel} video` : 'Property video'}
                    videoRef={activeVideoRef} stageRef={mediaStageRef} playing={videoControls.playing}
                    fullscreenMode={fullscreenMode} onFullscreenModeChange={setFullscreenMode}
                    playbackError={playbackError} onPlaybackError={setPlaybackError} onTogglePlayback={toggleActiveVideo}
                    onPlaybackChange={(playing, video) => {
                      if (activeVideoRef.current === video) setVideoControls(previous => nextQuickViewControlsState(previous, playing ? 'PLAY' : 'PAUSE'));
                    }} onActivity={revealControls}
                    onError={() => {
                      setVideoControls(previous => nextQuickViewControlsState(previous, 'PAUSE'));
                      setFailedMedia(previous => new Set(previous).add(activeMedia.url));
                    }} />
                : <img key={`${activeMedia.url}-${activeMediaIndex}`} draggable={false} src={buildCloudinaryUrl(activeMedia.url, 'DETAIL_MAIN') || activeMedia.url}
                  alt={activeMedia.tagLabel ? `${property.title} — ${activeMedia.tagLabel}` : property.title}
                  onError={() => setFailedMedia(previous => new Set(previous).add(activeMedia.url))} />
              : <div className="tenant-v0-quick-media-unavailable" role="img" aria-label="Property media unavailable"><HomeIcon size={30} aria-hidden="true" /><span>Property media unavailable</span></div>}
            {activeMedia?.tagLabel && <span className="tenant-v0-quick-media-tag" data-quick-view-control="true"><i aria-hidden="true" /><span className="tenant-v0-quick-media-tag-text">{activeMedia.tagLabel}</span></span>}
            {media.length > 0 && <span className="tenant-v0-quick-media-count" data-quick-view-control="true" aria-live="polite" aria-atomic="true">{activeMediaIndex + 1} / {media.length}</span>}
            <button ref={closeButtonRef} type="button" onClick={onClose} aria-label="Close property quick view" className={`tenant-v0-quick-close ${focusClass}`}><X size={18} aria-hidden="true" /></button>
          </div>
          <div className="tenant-v0-quick-panel">
            <div className="tenant-v0-quick-content">
              {locality && <p className="tenant-v0-quick-locality"><MapPin size={13} aria-hidden="true" />{locality}</p>}
              <h2 id="tenant-quick-view-property-title" className="tenant-v0-quick-title">{property.title}</h2>
              {price && <p className="tenant-v0-quick-price"><strong>{price}</strong><span>{property.listingType === 'RENT' ? '/ month' : 'asking price'}</span></p>}
              {facts.length > 0 && <div className="tenant-v0-quick-facts" aria-label="Property facts">{facts.map(fact => <span key={fact.label}><strong>{fact.value}</strong><small>{fact.label}</small></span>)}</div>}
              {description && <p className="tenant-v0-quick-description">{description}</p>}
              <div className="tenant-v0-quick-status"><Clock3 size={17} aria-hidden="true" /><span>A visit request is not a confirmed appointment.</span></div>
              <button type="button" onClick={() => onViewProperty(property)} className={`tenant-v0-quick-full ${focusClass}`}>View full property <ArrowRight size={15} aria-hidden="true" /></button>
            </div>
            <footer className="tenant-v0-quick-footer">
              <button type="button" onClick={() => onRequestVisit(property)} className={`tenant-v0-quick-request ${focusClass}`}>{tenantVisitCtaLabel(visitRequestStatus(property.id)) ?? 'Request visit'} <ArrowRight size={15} aria-hidden="true" /></button>
              <button type="button" onClick={() => onToggleFavorite(property)} disabled={!favoriteStateReady || favoritePending}
                aria-label={favoritePending ? 'Updating saved home' : isFavorite ? 'Remove from saved homes' : 'Save home'}
                aria-pressed={favoriteStateReady ? isFavorite : undefined}
                className={`tenant-v0-quick-save ${focusClass}`}>
                {favoritePending ? <LoaderCircle size={18} className="motion-safe:animate-spin" aria-hidden="true" /> : <Heart size={18} fill={isFavorite ? 'currentColor' : 'none'} aria-hidden="true" />}
              </button>
            </footer>
          </div>
        </>}
      </section>
    </div>
  );
};

type FavoriteState = FavoriteLookupState;

const initialFavoriteState: FavoriteState = { identityKey: null, status: 'loading', lookupError: false, propertyIds: new Set(), favoriteIds: new Set() };

type SavedHomesState = {
  identityKey: string | null;
  status: 'loading' | 'ready' | 'error';
  properties: Property[];
  page: number;
  hasMore: boolean;
  loadingMore: boolean;
};

const initialSavedHomesState: SavedHomesState = {
  identityKey: null, status: 'loading', properties: [], page: 0, hasMore: false, loadingMore: false
};

export const TenantDashboard: React.FC<TenantDashboardProps> = ({
  user, savedCount, properties, discoveryState, discoveryLoadedKey, discoveryCity, searchFilters, hasMoreProperties,
  loadingMoreProperties, loadMorePropertiesError, onSearchHomes, onRetryDiscovery,
  onLoadMoreProperties, onRequestVisit, onViewProperty, hasLessorCapability, onOpenLessor, onLogout
}) => {
  const reduceMotionPreference = useReducedMotion();
  const reduceMotion = reduceMotionPreference === true;
  const [history, setHistory] = useState<HistoryState>(emptyHistory);
  const [tenantSessions, setTenantSessions] = useState<TenantSessionHistory>(emptyTenantSessions);
  const [tenantOutcomeHistory, setTenantOutcomeHistory] = useState<TenantOutcomeHistory>(emptyTenantOutcomeHistory);
  const [tenantSessionsReload, setTenantSessionsReload] = useState(0);
  const [tenantSessionPage, setTenantSessionPage] = useState(0);
  const [tenantSessionCodes, setTenantSessionCodes] = useState<Record<number, TenantVisitStartCode>>({});
  const [tenantSessionAction, setTenantSessionAction] = useState<number | null>(null);
  const [tenantSessionError, setTenantSessionError] = useState<string | null>(null);
  const [visitClock, setVisitClock] = useState(() => Date.now());
  const tenantSessionActionRef = useRef(false);
  const [loadMorePending, setLoadMorePending] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState(false);
  const [reload, setReload] = useState(0);
  const pageRequestRef = useRef<AbortController | null>(null);
  const pagePendingRef = useRef(false);
  const location = useLocation();
  const navigate = useNavigate();
  const tenantView: 'home' | 'saved' | 'visits' | 'account' | 'notifications' = location.hash === '#saved-homes-title' ? 'saved'
    : location.hash === '#visit-history' || location.hash.startsWith('#visit-session-') ? 'visits'
      : location.hash === '#account' ? 'account'
        : location.hash === '#notifications' ? 'notifications' : 'home';
  const routeState = location.state && typeof location.state === 'object' ? location.state as Record<string, unknown> : {};
  const candidatePreviewId = Number(routeState.tenantQuickViewPropertyId);
  const previewPropertyId = routeState.tenantQuickView === true && Number.isSafeInteger(candidatePreviewId) && candidatePreviewId > 0
    ? candidatePreviewId : null;
  const propertyIdsKey = [...new Set(properties.map(property => property.id).filter(id => Number.isSafeInteger(id) && id > 0))].join(',');
  const [favoriteState, setFavoriteState] = useState<FavoriteState>(initialFavoriteState);
  const favoriteStateRef = useRef(favoriteState);
  const [favoriteReload, setFavoriteReload] = useState(0);
  const [favoritePendingIds, setFavoritePendingIds] = useState<Set<string>>(() => new Set());
  const [favoriteActionError, setFavoriteActionError] = useState<string | null>(null);
  const [savedHomesState, setSavedHomesState] = useState<SavedHomesState>(initialSavedHomesState);
  const [savedHomesReload, setSavedHomesReload] = useState(0);
  const [visitSessionOpenState, setVisitSessionOpenState] = useState<Record<number, boolean>>({});
  const [quickRefineSheetOpen, setQuickRefineSheetOpen] = useState(false);
  const pendingHeroFilterScrollRef = useRef<PendingTenantHeroFilterScroll | null>(null);
  const pendingHeroFilterScrollFrameRef = useRef<number | null>(null);
  const quickRefineOpenRef = useRef<(() => void) | null>(null);
  const submitSearchRef = useRef<(() => void) | null>(null);
  const savedHomesRequestRef = useRef<AbortController | null>(null);
  const savedHomesLoadingMoreRef = useRef(false);
  const favoriteMutationRef = useRef<Set<string>>(new Set());
  const favoriteMutationRevisionRef = useRef<Map<string, number>>(new Map());
  const favoriteSession = readTenantVisitSession(user.id);
  const savedHomesIdentityMatches = Boolean(favoriteSession && savedHomesState.identityKey === favoriteSession.key);
  const visibleSavedHomes = savedHomesIdentityMatches && savedHomesState.status !== "error"
    ? savedHomesState.properties : [];
  const visibleFavoriteIds = new Set<number>([
    ...(favoriteSession && favoriteState.identityKey === favoriteSession.key && favoriteState.status === "ready"
      ? favoriteState.favoriteIds : []),
    ...visibleSavedHomes.map(property => property.id)
  ]);
  const currentDiscoveryKey = discoverySearchKey(searchFilters);
  const mobileDockBadges = tenantMobileDockBadges(searchFilters, undefined, savedCount);
  const showTenantMobileDock = shouldRenderTenantMobileDock(
    false, quickRefineSheetOpen, previewPropertyId !== null
  );
  const mobileDockActiveItem: TenantMobileDockItem | 'account' | 'notifications' = quickRefineSheetOpen ? 'filters' : tenantView;
  const quickRefineRentBounds = useMemo(() => getQuickRefineRentBounds(searchFilters),
    [searchFilters.minRent, searchFilters.maxRent]);
  useEffect(() => {
    if (routeState.openTenantAccount !== true) return;
    navigate(`${location.pathname}${location.search}#account`, { replace: true, state: null });
  }, [routeState.openTenantAccount, location.pathname, location.search, navigate]);
  const reportQuickRefineSheetOpen = useCallback((isOpen: boolean) => {
    setQuickRefineSheetOpen(isOpen);
  }, []);
  const closeQuickView = useCallback(() => navigate(-1), [navigate]);
  const openQuickView = useCallback((property: Property) => {
    const prior = location.state && typeof location.state === 'object' && !Array.isArray(location.state)
      ? location.state as Record<string, unknown> : {};
    navigate(`${location.pathname}${location.search}${location.hash}`, {
      state: { ...prior, tenantQuickView: true, tenantQuickViewPropertyId: property.id, tenantQuickViewOpenerId: `tenant-property-${property.id}` }
    });
  }, [location.hash, location.pathname, location.search, location.state, navigate]);


  useEffect(() => {
    const pending = pendingHeroFilterScrollRef.current;
    const resolution = resolveTenantHeroFilterScroll(
      pending,
      currentDiscoveryKey,
      discoveryLoadedKey,
      discoveryState,
      location.key
    );
    if (resolution === 'discard') {
      pendingHeroFilterScrollRef.current = null;
      if (pendingHeroFilterScrollFrameRef.current !== null) {
        window.cancelAnimationFrame(pendingHeroFilterScrollFrameRef.current);
        pendingHeroFilterScrollFrameRef.current = null;
      }
      return;
    }
    if (resolution !== 'scroll') return;
    if (pendingHeroFilterScrollFrameRef.current !== null) return;

    const scheduledPending = pending;
    pendingHeroFilterScrollFrameRef.current = window.requestAnimationFrame(() => {
      pendingHeroFilterScrollFrameRef.current = null;
      if (pendingHeroFilterScrollRef.current !== scheduledPending) return;
      pendingHeroFilterScrollRef.current = null;
      document.getElementById('discover-homes')?.scrollIntoView({
        behavior: reduceMotion ? 'instant' : 'smooth',
        block: 'start'
      });
    });
  }, [currentDiscoveryKey, discoveryLoadedKey, discoveryState, location.key, reduceMotion]);

  useEffect(() => () => {
    if (pendingHeroFilterScrollFrameRef.current !== null) {
      window.cancelAnimationFrame(pendingHeroFilterScrollFrameRef.current);
    }
  }, []);

  useEffect(() => {
    favoriteStateRef.current = favoriteState;
  }, [favoriteState]);

  useEffect(() => {
    const session = readTenantVisitSession(user.id);
    const ids = propertyIdsKey ? propertyIdsKey.split(',').map(Number) : [];
    if (!session) {
      setFavoriteState({ ...initialFavoriteState, status: 'error' });
      return;
    }
    const controller = new AbortController();
    const current = favoriteStateRef.current;
    const knownState = current.identityKey === session.key
      ? current : { ...initialFavoriteState, identityKey: session.key };
    const unresolvedIds = unresolvedFavoriteIds(ids, knownState.propertyIds);
    const mutationRevision = favoriteMutationRevisionRef.current.get(session.key) || 0;
    setFavoriteActionError(null);
    if (unresolvedIds.length === 0) {
      setFavoriteState(currentState => currentState.identityKey === session.key
        ? { ...currentState, status: 'ready', lookupError: false }
        : { ...initialFavoriteState, identityKey: session.key, status: 'ready' });
      return () => controller.abort();
    }
    const hasResolvedState = knownState.status === 'ready' || knownState.propertyIds.size > 0;
    setFavoriteState(currentState => currentState.identityKey === session.key
      ? { ...currentState, status: hasResolvedState ? 'ready' : 'loading', lookupError: false }
      : { ...initialFavoriteState, identityKey: session.key });
    favoriteService.listForProperties(unresolvedIds, controller.signal).then(savedIds => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      if ((favoriteMutationRevisionRef.current.get(session.key) || 0) !== mutationRevision) {
        setFavoriteReload(value => value + 1);
        return;
      }
      setFavoriteState(currentState => {
        if (currentState.identityKey !== session.key) return currentState;
        return applyFavoriteLookupSuccess(currentState, unresolvedIds, savedIds);
      });
    }).catch(() => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      setFavoriteState(currentState => currentState.identityKey === session.key
        ? applyFavoriteLookupFailure(currentState)
        : { ...initialFavoriteState, identityKey: session.key, status: 'error', lookupError: true });
    });
    return () => controller.abort();
  }, [favoriteReload, propertyIdsKey, user.id]);

  useEffect(() => {
    const session = readTenantVisitSession(user.id);
    savedHomesRequestRef.current?.abort();
    savedHomesLoadingMoreRef.current = false;
    if (!session) {
      setSavedHomesState({ ...initialSavedHomesState, status: 'error' });
      return undefined;
    }

    const controller = new AbortController();
    savedHomesRequestRef.current = controller;
    setSavedHomesState(current => current.identityKey === session.key
      ? { ...current, status: 'loading', loadingMore: false }
      : { ...initialSavedHomesState, identityKey: session.key });

    favoriteService.listSavedProperties(0, controller.signal).then(result => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      setSavedHomesState({
        identityKey: session.key,
        status: 'ready',
        properties: mergeSavedHomes([], result.properties),
        page: result.page,
        hasMore: result.hasMore,
        loadingMore: false
      });
    }).catch(() => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      setSavedHomesState(current => current.identityKey === session.key
        ? { ...current, status: 'error', loadingMore: false }
        : { ...initialSavedHomesState, identityKey: session.key, status: 'error' });
    });

    return () => {
      controller.abort();
      if (savedHomesRequestRef.current === controller) savedHomesRequestRef.current = null;
    };
  }, [savedHomesReload, user.id]);

  useEffect(() => {
    const refresh = () => {
      savedHomesRequestRef.current?.abort();
      savedHomesLoadingMoreRef.current = false;
      favoriteMutationRef.current.clear();
      favoriteMutationRevisionRef.current.clear();
      setFavoritePendingIds(new Set());
      setFavoriteState(initialFavoriteState);
      setSavedHomesState(initialSavedHomesState);
      setFavoriteReload(value => value + 1);
      setSavedHomesReload(value => value + 1);
    };
    window.addEventListener('pathome_auth_changed', refresh);
    window.addEventListener('storage', refresh);
    return () => {
      window.removeEventListener('pathome_auth_changed', refresh);
      window.removeEventListener('storage', refresh);
    };
  }, []);

  const toggleFavorite = async (property: Property) => {
    const session = readTenantVisitSession(user.id);
    const favoriteStateMatches = Boolean(session && favoriteState.identityKey === session.key && favoriteState.status === 'ready');
    const savedStateMatches = Boolean(session && savedHomesState.identityKey === session.key && savedHomesState.status === 'ready');
    const isSaved = Boolean(favoriteStateMatches && favoriteState.favoriteIds.has(property.id)
      || savedStateMatches && savedHomesState.properties.some(item => item.id === property.id));
    const isKnownDiscoveryProperty = Boolean(favoriteStateMatches && favoriteState.propertyIds.has(property.id));
    if (!session || (!favoriteStateMatches && !isSaved)
      || (!isSaved && !isKnownDiscoveryProperty)) {
      setFavoriteActionError('Saved property status is unavailable. Retry and try again.');
      return;
    }
    const mutationKey = `${session.key}:${property.id}`;
    if (favoriteMutationRef.current.has(mutationKey)) return;
    favoriteMutationRef.current.add(mutationKey);
    setFavoritePendingIds(current => new Set(current).add(mutationKey));
    setFavoriteActionError(null);
    const wasSaved = isSaved;
    try {
      if (wasSaved) await favoriteService.remove(property.id);
      else await favoriteService.save(property.id);
      if (!isCurrentTenantVisitSession(session)) return;
      notifyTenantFavoriteChanged({ identityKey: session.key, propertyId: property.id, saved: !wasSaved });
      favoriteMutationRevisionRef.current.set(session.key,
        (favoriteMutationRevisionRef.current.get(session.key) || 0) + 1);
      setFavoriteState(current => {
        if (current.identityKey !== session.key) return current;
        return applyFavoriteMutation(current, property.id, !wasSaved);
      });
      savedHomesRequestRef.current?.abort();
      setSavedHomesState(current => current.identityKey === session.key && current.status === 'ready'
        ? { ...current, properties: applyPersistedSavedHomeChange(current.properties, property, !wasSaved) }
        : current);
      setSavedHomesReload(value => value + 1);
    } catch {
      if (isCurrentTenantVisitSession(session)) setFavoriteActionError(`Could not ${wasSaved ? 'remove' : 'save'} this property. Please try again.`);
    } finally {
      favoriteMutationRef.current.delete(mutationKey);
      setFavoritePendingIds(current => { const next = new Set(current); next.delete(mutationKey); return next; });
    }
  };

  const loadMoreSavedHomes = async () => {
    const session = readTenantVisitSession(user.id);
    if (!session || savedHomesLoadingMoreRef.current || savedHomesState.identityKey !== session.key
      || savedHomesState.status !== 'ready' || !savedHomesState.hasMore) return;
    const nextPage = savedHomesState.page + 1;
    const controller = new AbortController();
    savedHomesRequestRef.current?.abort();
    savedHomesRequestRef.current = controller;
    savedHomesLoadingMoreRef.current = true;
    setSavedHomesState(current => current.identityKey === session.key ? { ...current, loadingMore: true } : current);
    try {
      const result = await favoriteService.listSavedProperties(nextPage, controller.signal);
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      setSavedHomesState(current => current.identityKey === session.key
        ? { ...current, status: 'ready', properties: mergeSavedHomes(current.properties, result.properties), page: result.page, hasMore: result.hasMore, loadingMore: false }
        : current);
    } catch {
      if (!controller.signal.aborted && isCurrentTenantVisitSession(session)) {
        setSavedHomesState(current => current.identityKey === session.key ? { ...current, loadingMore: false } : current);
        setFavoriteActionError('Could not load more saved homes. Please try again.');
      }
    } finally {
      if (savedHomesRequestRef.current === controller) savedHomesRequestRef.current = null;
      savedHomesLoadingMoreRef.current = false;
    }
  };

  useEffect(() => {
    const refreshRequests = () => setReload(value => value + 1);
    const refreshIdentity = () => {
      refreshRequests();
      setTenantSessionPage(0);
      setTenantSessionsReload(value => value + 1);
    };
    const onStorage = (event: StorageEvent) => {
      if (event.key === 'pathome_auth_token' || event.key === 'pathome_user' || event.key === null) refreshIdentity();
    };
    window.addEventListener('pathome_auth_changed', refreshIdentity);
    window.addEventListener('pathome_visit_request_created', refreshRequests);
    window.addEventListener('pathome_tenant_visit_notification_opened', refreshIdentity);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('pathome_auth_changed', refreshIdentity);
      window.removeEventListener('pathome_visit_request_created', refreshRequests);
      window.removeEventListener('pathome_tenant_visit_notification_opened', refreshIdentity);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  useEffect(() => {
    const session = readTenantVisitSession(user.id);
    pageRequestRef.current?.abort();
    pagePendingRef.current = false;
    if (!session) {
      setHistory({ ...emptyHistory, status: 'error' });
      return;
    }
    const controller = new AbortController();
    setHistory({ ...emptyHistory, identityKey: session.key });
    setLoadMoreError(false);
    setLoadMorePending(false);
    tenantVisitService.list(0, controller.signal).then(page => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      if (!belongsToTenantVisitSession(session, page.userId)) {
        setHistory({ ...emptyHistory, identityKey: session.key, status: 'error' });
        return;
      }
      setHistory({ identityKey: session.key, status: 'ready', requests: page.requests,
        totalCount: page.totalCount, page: 0, hasMore: page.hasMore });
    }).catch(() => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      setHistory({ ...emptyHistory, identityKey: session.key, status: 'error' });
    });
    return () => { controller.abort(); pageRequestRef.current?.abort(); };
  }, [user.id, reload]);

  useEffect(() => {
    const identitySession = readTenantVisitSession(user.id);
    setTenantSessionCodes({});
    setTenantSessionError(null);
    if (!identitySession) {
      setTenantSessions({ ...emptyTenantSessions, status: 'error' });
      return undefined;
    }
    const controller = new AbortController();
    setTenantSessions({ ...emptyTenantSessions, identityKey: identitySession.key, status: 'loading' });
    visitExecutionService.listTenant(tenantSessionPage, controller.signal).then(page => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(identitySession)) return;
      if (page.totalPages > 0 && tenantSessionPage >= page.totalPages) {
        setTenantSessionPage(page.totalPages - 1);
        return;
      }
      setTenantSessions({ identityKey: identitySession.key, status: 'ready', sessions: Array.isArray(page.sessions) ? page.sessions : [], totalPages: page.totalPages });
    }).catch(() => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(identitySession)) return;
      setTenantSessions({ ...emptyTenantSessions, identityKey: identitySession.key, status: 'error' });
    });
    return () => controller.abort();
  }, [user.id, tenantSessionsReload, tenantSessionPage]);

  useEffect(() => {
    const identitySession = readTenantVisitSession(user.id);
    if (!identitySession) {
      setTenantOutcomeHistory({ ...emptyTenantOutcomeHistory, status: 'error' });
      return undefined;
    }
    const controller = new AbortController();
    setTenantOutcomeHistory({ ...emptyTenantOutcomeHistory, identityKey: identitySession.key, status: 'loading' });
    visitExecutionService.listTenantOutcomeHistory(tenantSessionPage, controller.signal).then(page => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(identitySession)) return;
      if (page.totalPages > 0 && tenantSessionPage >= page.totalPages) {
        setTenantSessionPage(page.totalPages - 1);
        return;
      }
      if (page.totalPages === 0 && tenantSessionPage !== 0) {
        setTenantSessionPage(0);
        return;
      }
      setTenantOutcomeHistory({ identityKey: identitySession.key, status: 'ready', sessions: Array.isArray(page.sessions) ? page.sessions : [] });
    }).catch(() => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(identitySession)) return;
      setTenantOutcomeHistory({ ...emptyTenantOutcomeHistory, identityKey: identitySession.key, status: 'error' });
    });
    return () => controller.abort();
  }, [user.id, tenantSessionsReload, tenantSessionPage]);

  const session = readTenantVisitSession(user.id);
  const visibleTenantSessions = !session ? { ...emptyTenantSessions, status: 'error' as const }
    : tenantSessions.identityKey === session.key ? tenantSessions : emptyTenantSessions;
  const visibleTenantOutcomeHistory = !session ? { ...emptyTenantOutcomeHistory, status: 'error' as const }
    : tenantOutcomeHistory.identityKey === session.key ? tenantOutcomeHistory : emptyTenantOutcomeHistory;
  const tenantOutcomesBySession = useMemo(() => new Map(visibleTenantOutcomeHistory.sessions.map(item => [item.sessionId, item])),
    [visibleTenantOutcomeHistory.sessions]);
  const issueVisitCode = async (sessionId: number) => {
    const requestSession = readTenantVisitSession(user.id);
    if (!requestSession) { setTenantSessionError('Please sign in again to manage this visit.'); return; }
    if (tenantSessionActionRef.current) return;
    tenantSessionActionRef.current = true;
    setTenantSessionAction(sessionId);
    setTenantSessionError(null);
    try {
      const code = await visitExecutionService.issueStartCode(sessionId);
      if (!isCurrentTenantVisitSession(requestSession)) return;
      setTenantSessionCodes(current => ({ ...current, [sessionId]: code }));
    } catch (error) {
      if (isCurrentTenantVisitSession(requestSession))
        setTenantSessionError(error instanceof Error ? error.message : 'Could not prepare a visit start code. Please retry.');
    } finally {
      tenantSessionActionRef.current = false;
      if (isCurrentTenantVisitSession(requestSession)) setTenantSessionAction(null);
    }
  };
  const confirmVisitChange = async (sessionId: number, action: 'ACCEPT_RESCHEDULE' | 'REJECT_RESCHEDULE' | 'DISPUTE_NO_SHOW', expectedSessionVersion: number) => {
    const requestSession = readTenantVisitSession(user.id);
    if (!requestSession) { setTenantSessionError('Please sign in again to manage this visit.'); return; }
    if (tenantSessionActionRef.current) return;
    tenantSessionActionRef.current = true;
    setTenantSessionAction(sessionId);
    setTenantSessionError(null);
    try {
      await visitExecutionService.confirmTenant(sessionId, action, createVisitOperationId(), expectedSessionVersion);
      if (!isCurrentTenantVisitSession(requestSession)) return;
      setTenantSessionCodes(current => { const next = { ...current }; delete next[sessionId]; return next; });
      setTenantSessionsReload(value => value + 1);
    } catch (error) {
      if (isCurrentTenantVisitSession(requestSession))
        setTenantSessionError(error instanceof Error ? error.message : 'Could not record your visit response. Please retry.');
    } finally {
      tenantSessionActionRef.current = false;
      if (isCurrentTenantVisitSession(requestSession)) setTenantSessionAction(null);
    }
  };
  useEffect(() => {
    const hasUpcomingCodeBoundary = (now: number) => Object.values(tenantSessionCodes).some(code =>
      (secondsUntilVisitCodeTime(code.expiresAt, now) ?? 0) > 0
      || (secondsUntilVisitCodeTime(code.nextRequestAt, now) ?? 0) > 0);
    const now = Date.now();
    setVisitClock(now);
    if (!hasUpcomingCodeBoundary(now)) return undefined;
    const interval = window.setInterval(() => {
      const tick = Date.now();
      setVisitClock(tick);
      if (!hasUpcomingCodeBoundary(tick)) window.clearInterval(interval);
    }, 1000);
    return () => window.clearInterval(interval);
  }, [tenantSessionCodes]);
  useEffect(() => {
    if (routeState.focusTenantVisits !== true) return;
    navigate(`${location.pathname}${location.search}#visit-history`, { replace: true, state: null });
  }, [routeState.focusTenantVisits, location.pathname, location.search, navigate]);
  useEffect(() => {
    const targetId = tenantView === 'visits' ? 'tenant-visits-page-title'
      : tenantView === 'saved' ? 'saved-homes-title'
        : tenantView === 'account' ? 'tenant-account-title'
          : tenantView === 'notifications' ? 'tenant-notifications-title' : 'tenant-home-search';
    const timer = window.setTimeout(() => {
      const target = document.getElementById(targetId);
      if (!target) return;
      window.scrollTo({ top: 0, behavior: 'instant' });
      target.focus({ preventScroll: true });
    }, 0);
    return () => window.clearTimeout(timer);
  }, [tenantView]);
  useEffect(() => {
    if (!location.hash.startsWith('#visit-session-')) return undefined;
    const sessionId = Number(location.hash.slice('#visit-session-'.length));
    if (!Number.isSafeInteger(sessionId) || sessionId <= 0) return undefined;
    setVisitSessionOpenState(current => current[sessionId] ? current : { ...current, [sessionId]: true });
    const timer = window.setTimeout(() => {
      document.getElementById(`visit-session-${sessionId}`)?.scrollIntoView({ block: 'start', behavior: 'instant' });
    }, 0);
    return () => window.clearTimeout(timer);
  }, [location.hash]);
  const visibleHistory: HistoryState = !session ? { ...emptyHistory, status: 'error' } :
    session.key === history.identityKey ? history : emptyHistory;
  const view = tenantVisitView(visibleHistory.status, visibleHistory.requests);
  const actionRequiredVisits = visibleTenantSessions.sessions.filter(visit =>
    visit.status === 'PROVISIONAL_NO_SHOW'
      || visit.status === 'SCHEDULED' && visit.tenantConfirmationState === 'PENDING'
        && (visit.repairState === 'NONE' || visit.repairState === 'PROPOSED'));
  const visitHistorySummary = visibleTenantSessions.sessions.length > 0 && visibleHistory.totalCount === 0
    ? `${visibleTenantSessions.sessions.length} visit ${visibleTenantSessions.sessions.length === 1 ? 'session' : 'sessions'}`
    : view === 'empty' && visibleTenantSessions.status === 'loading' ? 'Loading visit updates…'
      : view === 'empty' ? 'Track requests and visit updates'
        : tenantVisitSummary(view, visibleHistory.totalCount);
  const currentVisits = visibleTenantSessions.sessions.filter(visit =>
    ['DRAFT', 'SCHEDULED', 'STARTED', 'INTERRUPTED', 'PROVISIONAL_NO_SHOW', 'REPAIR_REQUIRED'].includes(visit.status));
  const pastVisits = visibleTenantSessions.sessions.filter(visit => !currentVisits.includes(visit));
  const coordinatingRequests = visibleHistory.requests.filter(request => request.status === 'RECEIVED' || request.status === 'COORDINATING');
  const sessionRequests = visibleHistory.requests.filter(request => request.status === 'SCHEDULED');
  const pastRequests = visibleHistory.requests.filter(request => request.status === 'UNAVAILABLE' || request.status === 'CANCELLED');
  const requestStatusForProperty = (propertyId: number): string | null => {
    if (view === 'loading' || view === 'error') return 'UNKNOWN';
    // The POST is tenant/listing-idempotent. An older request outside the loaded page returns its existing state.
    return tenantRequestStatusForProperty(visibleHistory.requests, propertyId);
  };
  const handleTenantRequestVisit = (property: Property) => {
    if (requestStatusForProperty(property.id)) {
      navigateMobileDock('visits');
      return;
    }
    onRequestVisit(property);
  };
  const availableProperties = properties;
  const favoriteIsReadyFor = (propertyId: number): boolean => Boolean(favoriteSession && (
    isFavoriteLookupReadyFor(favoriteState, favoriteSession.key, propertyId)
      || savedHomesIdentityMatches && savedHomesState.status === 'ready' && visibleSavedHomes.some(item => item.id === propertyId)
  ));
  const isPropertySaved = (propertyId: number): boolean => visibleFavoriteIds.has(propertyId);
  const isFavoritePending = (propertyId: number): boolean => Boolean(favoriteSession
    && favoritePendingIds.has(`${favoriteSession.key}:${propertyId}`));

  const applySmartSearch = (filters: RentalSearchFilters) => {
    onSearchHomes(filters);
    document.getElementById('discover-homes')?.scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start'
    });
  };

  const navigateMobileDock = (item: TenantMobileDockItem) => {
    if (item === 'filters') { quickRefineOpenRef.current?.(); return; }
    const hash = item === 'visits' ? '#visit-history' : item === 'saved' ? '#saved-homes-title' : '#tenant-home-search';
    if (location.hash !== hash) navigate(`${location.pathname}${location.search}${hash}`);
    else window.scrollTo({ top: 0, behavior: 'instant' });
  };

  const clearTenantSearch = () => {
    pendingHeroFilterScrollRef.current = null;
    if (pendingHeroFilterScrollFrameRef.current !== null) {
      window.cancelAnimationFrame(pendingHeroFilterScrollFrameRef.current);
      pendingHeroFilterScrollFrameRef.current = null;
    }
    onSearchHomes(resetFiltersForSearchClear(searchFilters.city || discoveryCity));
  };

  const toggleHeroQuickFilter = (kind: 'bhk' | 'propertyType', value: string) => {
    const nextFilters: RentalSearchFilters = {
      ...searchFilters,
      city: searchFilters.city || discoveryCity || undefined,
      rentalOnly: searchFilters.rentalOnly
    };
    if (kind === 'bhk') {
      const selected = searchFilters.bhk?.replace(/\s+/g, '').toUpperCase() === value.replace(/\s+/g, '').toUpperCase();
      nextFilters.bhk = selected ? undefined : value;
    } else {
      const selected = searchFilters.propertyType === value;
      nextFilters.propertyType = selected ? undefined : value as RentalPropertyType;
    }
    pendingHeroFilterScrollRef.current = queueTenantHeroFilterScroll(
      pendingHeroFilterScrollRef.current,
      discoverySearchKey(nextFilters),
      location.key
    );
    if (pendingHeroFilterScrollFrameRef.current !== null) {
      window.cancelAnimationFrame(pendingHeroFilterScrollFrameRef.current);
      pendingHeroFilterScrollFrameRef.current = null;
    }
    onSearchHomes(nextFilters);
  };

  const renderHeroQuickFilterControls = (mobile = false) => <>
    {HERO_BHK_FILTERS.map(value => {
      const selected = searchFilters.bhk?.replace(/\s+/g, '').toUpperCase() === value.replace(/\s+/g, '').toUpperCase();
      const mobileClass = selected
        ? 'border-[#dce5da] bg-[#edf2ed] text-[#355c49] shadow-none'
        : 'border-[#e9e7e1] bg-white text-[#61715d] shadow-none hover:border-[#cfd9cd] hover:bg-[#f3f5f0]';
      const desktopClass = selected
        ? 'border-[#dce5da] bg-[#edf2ed] text-[#355c49] shadow-none'
        : 'border-[#e9e7e1] bg-white text-[#61715d] hover:border-[#cfd9cd] hover:bg-[#f3f5f0]';
      return <button key={value} type="button" aria-pressed={selected} onClick={() => toggleHeroQuickFilter('bhk', value)}
        className={`inline-flex min-h-11 shrink-0 items-center gap-2 rounded-full border px-4 text-sm font-semibold transition-[background-color,border-color,color,transform] duration-200 focus-visible:outline-none focus-visible:ring-2 ${mobile ? 'focus-visible:ring-[#7b9b84]' : 'focus-visible:ring-[#7b9b84]'} motion-reduce:transition-none ${mobile ? mobileClass : desktopClass}`}>
        <BedDouble size={17} aria-hidden="true" />{value}
      </button>;
    })}
    {HERO_PROPERTY_FILTERS.map(({ label, value }) => {
      const selected = searchFilters.propertyType === value;
      const mobileClass = selected
        ? 'border-[#dce5da] bg-[#edf2ed] text-[#355c49] shadow-none'
        : 'border-[#e9e7e1] bg-white text-[#61715d] shadow-none hover:border-[#cfd9cd] hover:bg-[#f3f5f0]';
      const desktopClass = selected
        ? 'border-[#dce5da] bg-[#edf2ed] text-[#355c49] shadow-none'
        : 'border-[#e9e7e1] bg-white text-[#61715d] hover:border-[#cfd9cd] hover:bg-[#f3f5f0]';
      return <button key={value} type="button" aria-pressed={selected} onClick={() => toggleHeroQuickFilter('propertyType', value)}
        className={`inline-flex min-h-11 shrink-0 items-center gap-2 rounded-full border px-4 text-sm font-semibold transition-[background-color,border-color,color,transform] duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#7b9b84] motion-reduce:transition-none ${mobile ? mobileClass : desktopClass}`}>
        {value === 'FLAT' ? <Building2 size={17} aria-hidden="true" /> : <HomeIcon size={17} aria-hidden="true" />}{label}
      </button>;
    })}
  </>;

  const handleSmartSearch = (city?: string, sector?: string,
    filters?: Partial<RentalSearchFilters>) => {
    applySmartSearch({
      ...filters,
      city: city || filters?.city || discoveryCity || undefined,
      sector: sector || filters?.sector || undefined,
      rentalOnly: filters?.rentalOnly ?? true
    });
  };

  const loadMore = async () => {
    if (!session || visibleHistory.status !== 'ready' || !visibleHistory.hasMore || pagePendingRef.current) return;
    pagePendingRef.current = true;
    const controller = new AbortController();
    pageRequestRef.current = controller;
    setLoadMorePending(true);
    setLoadMoreError(false);
    try {
      const next = await tenantVisitService.list(visibleHistory.page + 1, controller.signal);
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      if (!belongsToTenantVisitSession(session, next.userId)) {
        setLoadMoreError(true);
        return;
      }
      setHistory(current => current.identityKey !== session.key || current.page !== visibleHistory.page ? current : {
        identityKey: session.key, status: 'ready',
        requests: appendUniqueVisitRequests(current.requests, next.requests),
        totalCount: next.totalCount, page: next.page, hasMore: next.hasMore
      });
    } catch {
      if (!controller.signal.aborted && isCurrentTenantVisitSession(session)) setLoadMoreError(true);
    } finally {
      if (pageRequestRef.current === controller) {
        pageRequestRef.current = null;
        pagePendingRef.current = false;
        setLoadMorePending(false);
      }
    }
  };

  return (
    <div className="tenant-shell-main relative min-w-0">
    {previewPropertyId === null && !quickRefineSheetOpen && <TenantNavigationRail
      activeItem={mobileDockActiveItem}
      onNavigate={navigateMobileDock}
      hasLessorCapability={hasLessorCapability}
      savedCount={savedCount}
      onOpenLessor={onOpenLessor}
      onOpenAccount={() => navigate(`${location.pathname}${location.search}#account`)}
      accountName={user.fullName || ''}
    />}
    <main aria-hidden={previewPropertyId !== null} className="tenant-v0-main min-w-0 bg-[#f8f7f4] pb-[calc(5rem+env(safe-area-inset-bottom))] text-[#252b25]">
      {tenantView === 'home' && <section id="tenant-home-search" tabIndex={-1} aria-label="Tenant home search" className="tenant-v0-search-hero relative z-20 min-w-0 scroll-mt-20 bg-[#f8f7f4] text-[#252b25] outline-none">
        <div className="tenant-v0-hero-inner">
          <div className="tenant-v0-welcome-row">
            <div>
              <p className="tenant-v0-eyebrow"><span aria-hidden="true" />A LITTLE MORE LIKE HOME</p>
              <h1 data-tenant-nav-focus tabIndex={-1}>Find a place<br className="tenant-v0-title-break" /> to <em>belong.</em></h1>
              <p className="tenant-v0-intro">Good homes. Clear next steps. A move that feels like yours.</p>
            </div>
            <aside className="tenant-v0-welcome-aside"><span><Sparkles size={18} aria-hidden="true" /></span><p>Thoughtful homes,<br /><strong>for your next chapter.</strong></p></aside>
          </div>
          <div className="tenant-v0-search-panel" aria-label="Find a home">
            <div id="tenant-search-placeholder" className="tenant-v0-search-context relative z-40 min-w-0">
              <CompactSearchContext appearance="tenant-hero" alwaysEditing stickyMode="hero" hideSubmitButton externalSubmitRef={submitSearchRef}
                searchPlaceholder="Neighbourhood or city" city={discoveryCity} filters={searchFilters}
                onSearch={handleSmartSearch}
                onManualCityChange={city => applySmartSearch(resetFiltersForManualCityChange(city))}
                onClearAll={clearTenantSearch} />
            </div>
            <label className="tenant-v0-home-type"><span>HOME TYPE</span><select value={searchFilters.propertyType || ''}
              onChange={event => onSearchHomes({ ...searchFilters, propertyType: event.target.value ? event.target.value as RentalPropertyType : undefined })}>
              <option value="">Any home</option>
              {QUICK_REFINE_PROPERTY_TYPES.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select></label>
            <button type="button" id="tenant-v0-filter-trigger" className="tenant-v0-filter-button" aria-haspopup="dialog" aria-expanded={quickRefineSheetOpen}
              onClick={() => quickRefineOpenRef.current?.()}><SlidersHorizontal size={17} aria-hidden="true" />Filters{mobileDockBadges.filterCount ? ` · ${mobileDockBadges.filterCount}` : ''}</button>
            <button type="button" className="tenant-v0-search-submit" onClick={() => submitSearchRef.current?.()}><Search size={17} aria-hidden="true" />Find homes</button>
          </div>
          <div className="tenant-v0-quick-refine" role="group" aria-label="Quick refine"><span>QUICK REFINE</span>{renderHeroQuickFilterControls()}</div>
        </div>
      </section>}

      <div className="tenant-v0-page mx-auto w-full min-w-0">
        {tenantView === 'home' && <>
        <section className="tenant-v0-reassurance" aria-label="What to expect">
          <span className="tenant-v0-reassurance-icon"><ShieldCheck size={20} aria-hidden="true" /></span>
          <span><strong>A little more peace of mind.</strong><small>Explore real homes, then request a visit when one feels right.</small></span>
          <button type="button" onClick={() => navigateMobileDock('visits')}>How visits work <ArrowRight size={15} aria-hidden="true" /></button>
        </section>
        <div className="tenant-v0-discovery">
          <section id="discover-homes" aria-labelledby="discover-homes-title" className="min-w-0 scroll-mt-20">
            <div className="tenant-v0-section-heading mb-4"><p className="tenant-v0-eyebrow">A GOOD PLACE TO START</p>
              <h2 id="discover-homes-title" tabIndex={-1} className="scroll-mt-20 focus:outline-none">Homes worth a closer look</h2>
              <p className="mt-1 text-sm text-slate-600">Browse current listings and request a visit when you find a fit.</p>
              <button type="button" onClick={() => navigateMobileDock('saved')} className="tenant-v0-saved-entry">Your saved homes <ArrowRight size={15} aria-hidden="true" /></button>
            </div>
            <TenantQuickRefineMobile filters={searchFilters} discoveryCity={discoveryCity} discoveryState={discoveryState}
              discoveryLoadedKey={discoveryLoadedKey} homesLoaded={properties.length} rentBounds={quickRefineRentBounds}
              onSearchHomes={onSearchHomes} openRequestRef={quickRefineOpenRef} onSheetOpenChange={reportQuickRefineSheetOpen} />
            {(favoriteState.status === 'error' || favoriteState.lookupError) && <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3" role="alert"><p className="text-sm text-amber-950">Saved property status could not be loaded.</p><button type="button" onClick={() => setFavoriteReload(value => value + 1)} className={`min-h-11 rounded-lg px-3 text-sm font-bold text-emerald-900 underline underline-offset-2 ${focusClass}`}>Retry</button></div>}
            {favoriteActionError && <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3" role="alert"><p className="text-sm text-rose-900">{favoriteActionError}</p><button type="button" onClick={() => setFavoriteActionError(null)} aria-label="Dismiss saved property message" className={`flex h-11 w-11 items-center justify-center rounded-lg text-rose-900 hover:bg-rose-100 ${focusClass}`}><X size={16} aria-hidden="true" /></button></div>}
            {discoveryState === 'LOADING' && <div className="grid gap-4 sm:grid-cols-2" role="status" aria-label="Loading available homes">
              {[0, 1, 2].map(index => <div key={index} className="overflow-hidden rounded-[26px] border border-slate-200 bg-white"><div className="aspect-[16/10] bg-slate-200 motion-safe:animate-pulse" /><div className="p-5"><div className="h-5 w-3/4 rounded bg-slate-200 motion-safe:animate-pulse" /><div className="mt-3 h-4 w-1/2 rounded bg-slate-100 motion-safe:animate-pulse" /><div className="mt-6 h-11 rounded-xl bg-slate-100 motion-safe:animate-pulse" /></div></div>)}
            </div>}
            {discoveryState === 'ERROR' && <div className="rounded-[26px] border border-slate-200 bg-white p-6 shadow-sm" role="alert"><p className="text-sm text-slate-700">Available homes could not be loaded right now.</p><button type="button" onClick={onRetryDiscovery} className={`mt-4 min-h-11 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 ${focusClass}`}>Retry</button></div>}
            {discoveryState === 'READY' && properties.length === 0 && <div className="flex min-w-0 items-start gap-4 rounded-[24px] border border-[#e1e5dc] bg-white p-5 shadow-sm sm:p-6">
              <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-[#eff5ef] text-emerald-800"><Search size={22} aria-hidden="true" /></span>
              <div className="min-w-0"><p className="font-['Outfit'] text-lg font-semibold text-slate-950">No homes match your search.</p>
                <p className="mt-1 text-sm leading-5 text-slate-600">Try another city, locality, or property type.</p>
                <a href="#tenant-home-search" className={`mt-3 inline-flex min-h-11 items-center gap-2 text-sm font-semibold text-emerald-800 underline-offset-4 hover:underline ${focusClass}`}>Adjust search <ArrowRight size={15} aria-hidden="true" /></a>
              </div>
            </div>}
            {discoveryState === 'READY' && availableProperties.length > 0 && <div className="tenant-v0-home-grid grid min-w-0 grid-cols-2 gap-4 min-[821px]:grid-cols-3">
              {availableProperties.map((property, index) => {
                const favoriteStateReady = favoriteIsReadyFor(property.id);
                return <SupportingPropertyCard key={property.id} property={property} visitRequestStatus={requestStatusForProperty(property.id)} onRequestVisit={handleTenantRequestVisit}
                  onOpenQuickView={openQuickView} isFavorite={isPropertySaved(property.id)}
                  favoriteStateReady={favoriteStateReady} favoritePending={isFavoritePending(property.id)}
                  onToggleFavorite={toggleFavorite} revealIndex={index} reduceMotion={reduceMotion} />;
              })}
            </div>}
            {discoveryState === 'READY' && hasMoreProperties && <div className="mt-8 flex flex-col items-center text-center">
              <button type="button" onClick={onLoadMoreProperties} disabled={loadingMoreProperties} aria-busy={loadingMoreProperties}
                className={`group inline-flex h-12 w-full max-w-[19rem] items-center gap-2 rounded-full border border-emerald-950/10 bg-gradient-to-r from-[#fffefa] via-white to-[#f2f8f2] px-3 text-sm font-semibold text-slate-800 shadow-[0_8px_22px_-16px_rgba(6,78,59,0.5),inset_0_1px_0_rgba(255,255,255,0.9)] transition-[transform,box-shadow,border-color] duration-[240ms] ease-out hover:-translate-y-0.5 hover:border-emerald-800/25 hover:shadow-[0_12px_26px_-15px_rgba(6,78,59,0.45),inset_0_1px_0_rgba(255,255,255,0.95)] active:translate-y-0 active:scale-[0.99] disabled:cursor-wait disabled:opacity-75 disabled:hover:translate-y-0 ${focusClass} motion-reduce:transform-none motion-reduce:transition-none`}>
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-emerald-50 text-emerald-800 ring-1 ring-emerald-900/5" aria-hidden="true">
                  {loadingMoreProperties ? <RefreshCw size={16} className="motion-safe:animate-spin" /> : <HomeIcon size={16} />}
                </span>
                <span className="min-w-0 flex-1 whitespace-nowrap">{loadingMoreProperties ? 'Loading more homes…' : 'Load more homes'}</span>
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-emerald-800" aria-hidden="true">
                  <ArrowRight size={17} className="transition-transform duration-200 group-hover:translate-x-0.5 group-focus-visible:translate-x-0.5 motion-reduce:transition-none" />
                </span>
              </button>
              {loadMorePropertiesError && <p role="alert" className="mt-2 text-sm text-rose-700">Could not load more homes. Please try again.</p>}
            </div>}
          </section>
        </div>
        </>}
        {tenantView === 'saved' && <div className="tenant-v0-saved">
        <section aria-labelledby="saved-homes-title" className="min-w-0">
          <div className="mb-3 flex flex-wrap items-end justify-between gap-x-4 gap-y-1">
            <div><p className="tenant-v0-eyebrow">YOUR SHORTLIST</p>
              <h1 id="saved-homes-title" data-tenant-nav-focus tabIndex={-1} className="tenant-v0-page-title mt-2 scroll-mt-24 focus:outline-none">The ones you <em>kept.</em></h1>
              <p className="tenant-v0-intro">Your saved homes, all in one place. Take your time.</p>
            </div>
            {visibleSavedHomes.length > 0 && <p className="text-xs text-slate-500">A small collection, kept in one place</p>}
          </div>

          {savedHomesIdentityMatches && savedHomesState.status === 'error' && <div className="mb-3 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50/90 px-3 py-2.5" role="alert">
            <p className="text-sm text-amber-950">Saved homes couldn’t be loaded right now.</p>
            <button type="button" onClick={() => setSavedHomesReload(value => value + 1)} className={`min-h-11 rounded-lg px-3 text-sm font-semibold text-emerald-900 underline underline-offset-2 ${focusClass}`}>Retry</button>
          </div>}

          {savedHomesIdentityMatches && savedHomesState.status === 'loading' && visibleSavedHomes.length === 0 && <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3" role="status" aria-label="Loading saved homes">
            {[0, 1].map(index => <div key={index} className="aspect-[1.48] rounded-[10px] border border-[#e9e7e1] bg-white motion-safe:animate-pulse" />)}
          </div>}

          {savedHomesIdentityMatches && savedHomesState.status === 'ready' && visibleSavedHomes.length === 0 && <div className="tenant-v0-saved-empty" role="status">
            <span><Heart size={22} aria-hidden="true" /></span>
            <h2>A little room for possibility.</h2>
            <p>When a home catches your eye, save it here and come back when you’re ready.</p>
            <button type="button" onClick={() => navigateMobileDock('home')}>Explore homes <ArrowRight size={16} aria-hidden="true" /></button>
          </div>}

          {visibleSavedHomes.length > 0 && <motion.div id="tenant-saved-home-grid" layout={!reduceMotion}
            className="tenant-v0-home-grid grid min-w-0 grid-cols-2 gap-3 min-[821px]:grid-cols-3"
            aria-label="Your saved homes">
              {visibleSavedHomes.map((property, index) => <motion.div key={property.id} layout={!reduceMotion}
                initial={reduceMotion ? false : { opacity: 0, y: 9 }} animate={{ opacity: 1, y: 0 }} exit={reduceMotion ? undefined : { opacity: 0, y: -5 }}
                transition={reduceMotion ? { duration: 0 } : { duration: 0.3, delay: Math.min(index, 5) * 0.055, ease: [0.16, 1, 0.3, 1] }}>
                <SavedHomeCard property={property} visitRequestStatus={requestStatusForProperty(property.id)} isFavorite={isPropertySaved(property.id)} favoriteStateReady={favoriteIsReadyFor(property.id)}
                  favoritePending={isFavoritePending(property.id)} onToggleFavorite={toggleFavorite}
                  onOpenQuickView={openQuickView} onRequestVisit={handleTenantRequestVisit} />
              </motion.div>)}
          </motion.div>}

          {savedHomesIdentityMatches && savedHomesState.status === 'ready' && savedHomesState.hasMore && <div className="mt-1 text-right">
            <button type="button" onClick={loadMoreSavedHomes} disabled={savedHomesState.loadingMore} className={`inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-sm font-semibold text-emerald-900 hover:bg-emerald-50 disabled:opacity-60 ${focusClass}`}>
              {savedHomesState.loadingMore ? <><RefreshCw size={15} className="motion-safe:animate-spin" aria-hidden="true" />Loading saved homes…</> : 'Show more saved homes'}
            </button>
          </div>}
        </section>
        </div>}
        {tenantView === 'visits' && <section className="tenant-v0-visits" aria-labelledby="tenant-visits-page-title">
          <p className="tenant-v0-eyebrow">THE NEXT STEP, AT YOUR PACE</p>
          <h1 id="tenant-visits-page-title" data-tenant-nav-focus tabIndex={-1} className="tenant-v0-page-title focus:outline-none">A look around, <em>soon.</em></h1>
          <p className="tenant-v0-intro">Visit requests and confirmed sessions, kept clear and separate.</p>
          <div id="visit-history" className="tenant-v0-visits-content min-w-0 space-y-4">
            <p className="tenant-v0-visit-summary">{visitHistorySummary}</p>
            {actionRequiredVisits.length > 0 && <section aria-labelledby="tenant-visit-action-title" className="rounded-[9px] border border-amber-300 bg-amber-50 px-4 py-3.5">
              <h3 id="tenant-visit-action-title" className="font-['Outfit'] text-sm font-semibold text-amber-950">Action Required</h3>
              <p className="mt-1 text-sm leading-5 text-amber-950">{actionRequiredVisits.length === 1 ? 'A visit needs your response.' : `${actionRequiredVisits.length} visits need your response.`}</p>
              <div className="mt-2 flex flex-wrap gap-2">
                {actionRequiredVisits.map(visit => <a key={visit.sessionId} href={`#visit-session-${visit.sessionId}`}
                  className="inline-flex min-h-11 items-center gap-1.5 rounded-lg bg-white px-3 text-sm font-semibold text-amber-950 underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-700">{visit.status === 'PROVISIONAL_NO_SHOW' ? 'Review attendance' : 'Review proposed time'} <ArrowRight size={14} aria-hidden="true" /></a>)}
              </div>
            </section>}
            <details className="rounded-lg text-xs text-slate-600">
              <summary className={`min-h-11 cursor-pointer py-2 font-semibold text-emerald-900 ${focusClass}`}>How visit credits work</summary>
              <p className="pb-2 leading-5">A Visit Session can include multiple suitable homes in the same area. Sending a request doesn’t use a visit credit. A confirmed schedule reserves one; starting with your in-person code consumes it. No homes viewed does not automatically restore it.</p>
            </details>
            {visibleTenantSessions.sessions.length > 0 && <section aria-labelledby="confirmed-visit-sessions-title" className="space-y-2">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <h3 id="confirmed-visit-sessions-title" className="font-['Outfit'] text-sm font-semibold text-slate-950">Visit sessions</h3>
                </div>
                {(visibleTenantSessions.status === 'error' || visibleTenantOutcomeHistory.status === 'error') && <button type="button" onClick={() => setTenantSessionsReload(value => value + 1)} className={`min-h-11 shrink-0 rounded-lg px-3 text-xs font-semibold text-emerald-900 underline ${focusClass}`}>Retry</button>}
              </div>
              {tenantSessionError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs text-rose-900">{tenantSessionError}</p>}
              {visibleTenantSessions.status === 'loading' && <p role="status" className="text-sm text-slate-600">Loading visit sessions…</p>}
              {visibleTenantSessions.status === 'error' && <p role="alert" className="text-sm text-slate-600">Visit session details are unavailable right now.</p>}
              {visibleTenantOutcomeHistory.status === 'loading' && visibleTenantSessions.status === 'ready' && <p role="status" className="text-xs text-slate-600">Loading visit outcome details…</p>}
              {visibleTenantOutcomeHistory.status === 'error' && <p role="alert" className="text-xs text-rose-800">Visit outcome details are unavailable right now. Retry to reload your visit history.</p>}
              {visibleTenantSessions.status === 'ready' && (() => {
                const renderVisitSession = (visit: VisitExecutionView) => {
                const outcome = tenantOutcomesBySession.get(visit.sessionId);
                const code = tenantSessionCodes[visit.sessionId];
                const isPending = tenantSessionAction === visit.sessionId;
                const needsTenantConfirmation = visit.tenantConfirmationState === 'PENDING';
                const statusLabel = visit.repairState === 'PROPOSED' ? 'New time proposed' : visit.repairState === 'REQUIRED' ? 'Schedule under review' : needsTenantConfirmation && visit.repairState === 'NONE' ? 'Confirmation needed' : tenantVisitOutcomeStatusLabel(outcome, visit.status);
                const visitDescription = visit.status === 'INTERRUPTED' ? 'Visit interrupted; Operations is reviewing recovery' : visit.repairState === 'REQUIRED' ? 'Schedule under review' : visit.repairState === 'PROPOSED' ? 'New time proposed' : needsTenantConfirmation ? 'Time change awaiting your response' : outcome?.lifecycle === 'RESULTS_NOT_RECORDED' ? 'Results not recorded' : outcome?.outcomeReportAvailable ? tenantVisitOutcomeSummaryText(outcome) : visit.status === 'STARTED' ? 'Visit in progress' : visit.status === 'DRAFT' ? 'Visit time is being arranged' : visit.status === 'CANCELLED' ? 'Visit cancelled' : visit.status === 'NO_SHOW' ? 'Visit marked no-show' : visit.status === 'EXPIRED' ? 'Visit expired' : visit.status === 'COMPLETED' ? 'Visit ended · details pending' : visit.status === 'PROVISIONAL_NO_SHOW' ? 'Attendance under review' : visit.status === 'SCHEDULED' ? 'Confirmed guided visit' : 'Scheduled';
                const showScheduledTime = visit.status !== 'INTERRUPTED' && visit.repairState !== 'REQUIRED' && visit.status !== 'DRAFT';
                const secondsRemaining = code ? secondsUntilVisitCodeTime(code.expiresAt, visitClock) : null;
                const nextCodeRequestIn = code ? secondsUntilVisitCodeTime(code.nextRequestAt, visitClock) : null;
                const codeExpired = secondsRemaining === null || secondsRemaining === 0;
                const needsDefaultOpen = actionRequiredVisits.some(item => item.sessionId === visit.sessionId) || visit.status === 'STARTED';
                return <article id={`visit-session-${visit.sessionId}`} key={visit.sessionId} className="min-w-0 scroll-mt-24">
                  <details open={visitSessionOpenState[visit.sessionId] ?? needsDefaultOpen}
                    onToggle={event => {
                      const open = event.currentTarget.open;
                      setVisitSessionOpenState(current => current[visit.sessionId] === open
                        ? current : { ...current, [visit.sessionId]: open });
                    }} className="tenant-v0-session-card group min-w-0 rounded-xl border border-emerald-100 bg-white shadow-sm">
                  <summary className={`flex min-h-11 min-w-0 cursor-pointer items-center gap-2 p-3 ${focusClass}`}>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2 py-1 text-[10px] font-semibold text-emerald-900">{statusLabel}</span>
                        {visit.repairState === 'PROPOSED' && <span className="rounded-full border border-sky-200 bg-sky-50 px-2 py-1 text-[10px] font-semibold text-sky-900">Proposed time</span>}
                        {visit.repairState === 'REQUIRED' && <span className="rounded-full border border-amber-200 bg-amber-50 px-2 py-1 text-[10px] font-semibold text-amber-900">Action required</span>}
                      </div>
                      <p className="mt-1 break-words text-xs font-semibold leading-4 text-slate-950">{visitDescription}{showScheduledTime && <> · {formatVisitTime(visit.scheduledAt, visit.zoneId)}</>}</p>
                    </div>
                    <ChevronRight size={16} className="shrink-0 text-emerald-800 transition-transform group-open:rotate-90 motion-reduce:transition-none" aria-hidden="true" />
                  </summary>
                  <div className="space-y-3 border-t border-slate-100 p-3">
                  {(() => {
                    const locality = [outcome?.locality, outcome?.city].map(readText).filter(Boolean).join(', ');
                    return locality ? <p className="mt-2 flex items-start gap-1.5 text-xs text-slate-600"><MapPin size={15} className="mt-0.5 shrink-0" aria-hidden="true" /><span>{locality}</span></p> : null;
                  })()}
                  {outcome?.outcomeReportAvailable && <div className="tenant-v0-outcome-panel mt-3 rounded-lg border border-emerald-100 bg-emerald-50/60 p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="text-sm font-semibold text-slate-900">Visit outcomes</p>
                      {outcome.outcomeSummary && <span className="text-xs font-semibold text-emerald-900">{tenantOutcomeSummaryLabel(outcome.outcomeSummary)}</span>}
                    </div>
                    <p className="mt-1 text-xs leading-5 text-slate-700">{tenantVisitOutcomeSummaryText(outcome)}</p>
                    {outcome.properties.length > 0 && <details className="mt-2 rounded-lg border border-emerald-100 bg-white p-2.5">
                      <summary className={`min-h-11 cursor-pointer py-2 text-xs font-semibold text-emerald-900 ${focusClass}`}>View homes in this visit</summary>
                      <ul className="mt-1 space-y-2">
                        {outcome.properties.map(property => <li key={`${outcome.sessionId}-${property.position}`} className="border-t border-slate-100 pt-2 first:border-0 first:pt-0">
                          <p className="text-sm font-medium text-slate-900">{property.title}</p>
                          <p className="text-xs text-slate-600">{[property.address, property.sector, property.city].filter(Boolean).join(' · ')}</p>
                          <p className="mt-1 text-xs font-semibold text-slate-800">{tenantPropertyOutcomeLabel(property)}</p>
                          {property.reasonLabel && <p className="mt-1 text-xs leading-5 text-slate-600">{property.reasonLabel}</p>}
                          {property.attribution === 'OPERATIONS_UPDATED' && property.correctedAt && <p className="mt-1 text-xs text-slate-500">Updated by Pathome Operations · {formatVisitTime(property.correctedAt)}</p>}
                        </li>)}
                      </ul>
                    </details>}
                  </div>}
                  {visit.arrivedAt && <p className="mt-1 text-xs font-medium text-emerald-900">Ground Executive arrived · {formatVisitTime(visit.arrivedAt, visit.zoneId)}.</p>}
                  {visit.startedAt && <p className="mt-1 text-xs text-slate-600">Started {formatVisitTime(visit.startedAt, visit.zoneId)}{visit.expectedEndAt ? ` · expected end ${formatVisitTime(visit.expectedEndAt, visit.zoneId)}` : ''}{visit.overPlannedTime ? ' · running over planned time' : ''}</p>}
                  {visit.tenantEtaAt && <p className="mt-1 text-xs text-slate-600">Reported visit ETA: {formatVisitTime(visit.tenantEtaAt, visit.zoneId)}.</p>}
                  {visit.repairState === 'PROPOSED' && <p className="mt-2 rounded-lg bg-sky-50 p-2 text-xs leading-5 text-sky-950">This time is proposed and will be confirmed only after you accept it.</p>}
                  {visit.repairState === 'REQUIRED' && <p className="mt-2 rounded-lg bg-amber-50 p-2 text-xs leading-5 text-amber-950">Your earlier visit time can no longer be confirmed. Operations is arranging a safe option and will update you.</p>}
                  {visit.status === 'DRAFT' && visit.repairState === 'NONE' && <p className="mt-2 rounded-lg bg-slate-50 p-2 text-xs leading-5 text-slate-700">A visit time is not confirmed yet. You can request a start code after a time is scheduled.</p>}
                  {needsTenantConfirmation && (visit.repairState === 'NONE' || visit.repairState === 'PROPOSED') && <div className="mt-3 space-y-2">
                    <p className="text-xs leading-5 text-slate-700">{visit.repairState === 'PROPOSED' ? 'A safe visit time is proposed. Confirm it only if it works for you.' : 'Operations proposed a time change. Confirm it only if the proposed visit time works for you.'}</p>
                    <div className="flex flex-wrap gap-2">
                      <button type="button" disabled={isPending} onClick={() => void confirmVisitChange(visit.sessionId, 'ACCEPT_RESCHEDULE', visit.version)} className={`min-h-11 rounded-xl bg-emerald-700 px-4 text-xs font-semibold text-white hover:bg-emerald-800 disabled:opacity-60 ${focusClass}`}>Accept new time</button>
                      <button type="button" disabled={isPending} onClick={() => void confirmVisitChange(visit.sessionId, 'REJECT_RESCHEDULE', visit.version)} className={`min-h-11 rounded-xl border border-slate-300 bg-white px-4 text-xs font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-60 ${focusClass}`}>Decline time</button>
                    </div>
                  </div>}
                  {visit.status === 'PROVISIONAL_NO_SHOW' && <button type="button" disabled={isPending} onClick={() => void confirmVisitChange(visit.sessionId, 'DISPUTE_NO_SHOW', visit.version)} className={`mt-3 min-h-11 rounded-xl border border-amber-300 bg-amber-50 px-4 text-xs font-semibold text-amber-950 hover:bg-amber-100 disabled:opacity-60 ${focusClass}`}>Dispute provisional no-show</button>}
                  {visit.status === 'SCHEDULED' && !needsTenantConfirmation && visit.repairState === 'NONE' && <div className="mt-3">
                    {code ? <div className="tenant-v0-pass-panel rounded-2xl border border-emerald-200 bg-[#eef5ec] p-4" role="status" aria-live="polite">
                      <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-emerald-900">Your Visit Pass</p>
                      {codeExpired ? <p className="mt-2 text-sm font-semibold text-slate-900">This code has expired. Request a fresh code when you’re with your Ground Executive.</p>
                        : <><p className="mt-2 font-mono text-3xl font-bold tracking-[0.3em] text-emerald-950 sm:text-4xl" aria-label="Visit start code">{code.code}</p><p className="mt-2 text-xs font-medium text-emerald-950">{secondsRemaining === null ? `Expires ${formatVisitTime(code.expiresAt)}` : `Expires in ${String(Math.floor(secondsRemaining / 60)).padStart(2, '0')}:${String(secondsRemaining % 60).padStart(2, '0')}`}</p></>}
                      <p className="mt-2 text-sm leading-5 text-slate-700">Share this code with your Ground Executive in person to begin your visit. Showing the code does not start the visit.</p>
                      <button type="button" disabled={isPending || (nextCodeRequestIn !== null && nextCodeRequestIn > 0)} onClick={() => void issueVisitCode(visit.sessionId)} className={`mt-2 min-h-11 rounded-lg px-3 text-sm font-semibold text-emerald-900 underline underline-offset-2 disabled:opacity-50 ${focusClass}`}>{nextCodeRequestIn !== null && nextCodeRequestIn > 0 ? `New code available in ${String(Math.floor(nextCodeRequestIn / 60)).padStart(2, '0')}:${String(nextCodeRequestIn % 60).padStart(2, '0')}` : codeExpired ? 'Request a fresh code' : 'Get a new code'}</button>
                    </div> : <button type="button" disabled={isPending} onClick={() => void issueVisitCode(visit.sessionId)} className={`min-h-11 rounded-xl bg-emerald-700 px-4 text-sm font-semibold text-white hover:bg-emerald-800 disabled:opacity-60 ${focusClass}`}>Get visit start code</button>}
                  </div>}
                  </div>
                  </details>
                </article>;
                };
                return <>
                  {currentVisits.length > 0 && <section aria-labelledby="upcoming-visit-sessions-title" className="space-y-3"><div><h4 id="upcoming-visit-sessions-title" className="text-sm font-semibold text-slate-900">Upcoming and active</h4><p className="mt-1 text-xs text-slate-600">Confirmed times, ongoing visits, and schedule updates.</p></div>{currentVisits.map(renderVisitSession)}</section>}
                  {pastVisits.length > 0 && <section aria-labelledby="past-visit-sessions-title" className="space-y-3"><h4 id="past-visit-sessions-title" className="text-sm font-semibold text-slate-900">Past visits</h4>{pastVisits.map(renderVisitSession)}</section>}
                </>;
              })()}
              {visibleTenantSessions.status === 'ready' && visibleTenantSessions.totalPages > 1 && <nav aria-label="Visit session pages" className="flex items-center justify-between gap-3 text-sm text-slate-700">
                <button type="button" disabled={tenantSessionPage === 0} onClick={() => setTenantSessionPage(value => value - 1)} className={`min-h-11 rounded-lg px-3 font-semibold disabled:opacity-50 ${focusClass}`}>Previous</button>
                <span>Page {tenantSessionPage + 1} of {visibleTenantSessions.totalPages}</span>
                <button type="button" disabled={tenantSessionPage + 1 >= visibleTenantSessions.totalPages} onClick={() => setTenantSessionPage(value => value + 1)} className={`min-h-11 rounded-lg px-3 font-semibold disabled:opacity-50 ${focusClass}`}>Next</button>
              </nav>}
            </section>}
            {visibleTenantSessions.sessions.length === 0 && (visibleTenantSessions.status === 'loading' || visibleTenantSessions.status === 'error'
              || visibleTenantOutcomeHistory.status === 'loading' || visibleTenantOutcomeHistory.status === 'error') && <div className="space-y-2 text-sm text-slate-600">
              {visibleTenantSessions.status === 'loading' && <p role="status">Loading visit sessions…</p>}
              {visibleTenantSessions.status === 'error' && <div role="alert" className="flex flex-wrap items-center gap-2"><span>Visit session details are unavailable right now.</span><button type="button" onClick={() => setTenantSessionsReload(value => value + 1)} className={`min-h-11 rounded-lg px-2 font-semibold text-emerald-900 underline ${focusClass}`}>Retry</button></div>}
              {visibleTenantSessions.sessions.length === 0 && visibleTenantOutcomeHistory.status === 'loading' && <p role="status">Loading visit outcome details…</p>}
              {visibleTenantSessions.sessions.length === 0 && visibleTenantOutcomeHistory.status === 'error' && <div role="alert" className="flex flex-wrap items-center gap-2"><span>Visit outcome details are unavailable right now.</span><button type="button" onClick={() => setTenantSessionsReload(value => value + 1)} className={`min-h-11 rounded-lg px-2 font-semibold text-emerald-900 underline ${focusClass}`}>Retry</button></div>}
            </div>}
            {view === 'loading' && <p className="py-1 text-xs text-slate-600" role="status" aria-live="polite">Loading visit requests…</p>}

            {view === 'error' && <div className="flex flex-wrap items-center gap-2 text-xs text-rose-900" role="alert">
              <span>Visit requests are unavailable.</span>
              <button type="button" onClick={() => setReload(value => value + 1)} className={`inline-flex min-h-11 items-center gap-1.5 rounded-lg px-2 font-semibold underline ${focusClass}`}><RefreshCw size={14} aria-hidden="true" /> Retry</button>
            </div>}

            {view === 'empty' && visibleTenantSessions.status === 'ready' && visibleTenantSessions.sessions.length === 0 && visibleTenantOutcomeHistory.status === 'ready' && visibleTenantOutcomeHistory.sessions.length === 0 && <div className="py-1" role="status" aria-live="polite">
              <div className="relative min-w-0"><h3 className="font-['Outfit'] text-sm font-semibold tracking-tight">No visits yet</h3>
                <p className="mt-1 text-xs leading-5 text-slate-600">Request a visit from a property to track it here.</p>
                <button type="button" onClick={() => navigateMobileDock('home')} className={`mt-1 inline-flex min-h-11 items-center gap-2 rounded-lg px-2 text-xs font-semibold text-emerald-800 transition-colors hover:bg-emerald-50 ${focusClass}`}>Explore homes <ArrowRight size={14} aria-hidden="true" /></button>
              </div>
            </div>}

            {coordinatingRequests.length > 0 && <section aria-labelledby="coordinating-requests-title" className="space-y-3">
              <div><h3 id="coordinating-requests-title" className="font-['Outfit'] text-sm font-semibold text-slate-950">Being coordinated</h3><p className="mt-0.5 text-[11px] leading-4 text-slate-600">Your preferred timing is a request. Pathome confirms availability before scheduling.</p></div>
              <div className="space-y-2">{coordinatingRequests.map(request => <TenantVisitRequestCard key={request.requestId} request={request} />)}</div>
            </section>}

            {sessionRequests.length > 0 && <details className="rounded-lg border-t border-slate-200 pt-1">
              <summary className={`flex min-h-11 cursor-pointer items-center justify-between gap-2 py-1 text-sm font-semibold text-slate-900 ${focusClass}`}>
                <span id="session-requests-title">Added to a Visit Session</span><span className="text-xs font-medium text-slate-600">{sessionRequests.length}</span>
              </summary>
              <div className="space-y-2 pb-2">{sessionRequests.map(request => <TenantVisitRequestCard key={request.requestId} request={request} />)}</div>
            </details>}

            {pastRequests.length > 0 && <details className="rounded-lg border-t border-slate-200 pt-1">
              <summary className={`flex min-h-11 cursor-pointer items-center justify-between gap-2 py-1 text-sm font-semibold text-slate-900 ${focusClass}`}>
                <span id="past-requests-title">Past requests</span><span className="text-xs font-medium text-slate-600">{pastRequests.length}</span>
              </summary>
              <div className="space-y-2 pb-2">{pastRequests.map(request => <TenantVisitRequestCard key={request.requestId} request={request} />)}</div>
            </details>}

            {view === 'populated' && visibleHistory.hasMore && <div className="text-center"><button type="button" onClick={loadMore} disabled={loadMorePending} className={`min-h-11 rounded-xl border border-slate-300 bg-white px-6 text-sm font-semibold text-slate-800 transition-colors hover:border-emerald-700 hover:bg-emerald-50 disabled:opacity-60 ${focusClass}`}>{loadMorePending ? <><RefreshCw size={15} className="mr-2 inline motion-safe:animate-spin" aria-hidden="true" />Loading more…</> : 'Load more requests'}</button>
              {loadMoreError && <p role="alert" className="mt-2 text-sm text-rose-700">Could not load more requests. Please try again.</p>}</div>}
          </div>
        </section>}
        {tenantView === 'account' && <section className="tenant-v0-account" aria-labelledby="tenant-account-title">
          <p className="tenant-v0-eyebrow"><span aria-hidden="true" />YOUR PATHOME ACCOUNT</p>
          <h1 id="tenant-account-title" data-tenant-nav-focus tabIndex={-1} className="tenant-v0-page-title">Make yourself <em>at home.</em></h1>
          <p className="tenant-v0-intro">Your profile and the things that make Pathome yours.</p>
          <div className="tenant-v0-profile-layout">
            <section className="tenant-v0-profile-card" aria-label="Your profile">
              <div className="tenant-v0-profile-cover">
                <span className="tenant-v0-profile-avatar" aria-hidden="true">{user.fullName?.trim().charAt(0).toUpperCase() || 'T'}</span>
                <div><h2>{user.fullName?.trim() || 'Your account'}</h2><p>Tenant account</p></div>
              </div>
              <div className="tenant-v0-profile-fields">
                {user.email?.trim() && <div><span>EMAIL ADDRESS</span><strong className="break-all">{user.email}</strong></div>}
                <div><span>CAPABILITIES ON THIS ACCOUNT</span><strong>{hasLessorCapability === true ? 'Tenant + lessor' : hasLessorCapability === false ? 'Tenant' : hasLessorCapability === 'error' ? 'Property access unavailable' : 'Checking account access…'}</strong></div>
              </div>
              {hasLessorCapability === 'error' && <div className="tenant-v0-profile-capability-error" role="alert">Property access could not be checked. <button type="button" onClick={() => window.dispatchEvent(new Event('pathome_auth_changed'))}>Try again</button></div>}
              <div className="tenant-v0-profile-actions">
                {hasLessorCapability !== 'error' && <button type="button" onClick={onOpenLessor} className="tenant-v0-profile-outline">{hasLessorCapability === true ? 'Your listings' : 'List your home'} <ArrowRight size={15} aria-hidden="true" /></button>}
              </div>
            </section>
            <aside className="tenant-v0-profile-security">
              <p className="tenant-v0-eyebrow">ACCOUNT &amp; SECURITY</p>
              <h2>Keep your account yours.</h2>
              <p>Sign out when you finish using a shared device.</p>
              <button type="button" onClick={onLogout}><LogOut size={16} aria-hidden="true" />Log out <ArrowRight size={15} aria-hidden="true" /></button>
            </aside>
          </div>
        </section>}
        {tenantView === 'notifications' && <section className="tenant-v0-notifications-section" aria-labelledby="tenant-notifications-title">
          <p className="tenant-v0-eyebrow"><span aria-hidden="true" />KEEPING YOU IN THE LOOP</p>
          <h1 id="tenant-notifications-title" tabIndex={-1} className="tenant-v0-page-title">A little <em>update.</em></h1>
          <p className="tenant-v0-intro">Relevant changes to your visits, homes and account.</p>
          <div id="tenant-notifications-root" className="tenant-v0-notifications-root" />
        </section>}
        {tenantView === 'home' && <section className="tenant-v0-lessor-banner">
          <div><span className="tenant-v0-eyebrow">FOR THE PEOPLE OPENING THEIR DOORS</span>
            <h2>A good tenant relationship<br />starts before the keys.</h2>
            <p>List your place with the details people actually need.</p>
          </div>
          <button type="button" onClick={onOpenLessor}>List your home <ArrowRight size={16} aria-hidden="true" /></button>
        </section>}
      </div>
      <footer className="tenant-v0-footer"><span>Your dreams, our efforts.</span><span>Pathome</span></footer>
    </main>
    {showTenantMobileDock && <TenantMobileDock activeItem={mobileDockActiveItem} savedCount={mobileDockBadges.savedCount}
      visitCount={mobileDockBadges.visitCount} hasLessorCapability={hasLessorCapability}
      onNavigate={navigateMobileDock} onOpenListings={onOpenLessor} />}
    {previewPropertyId !== null && <TenantPropertyQuickView key={previewPropertyId} propertyId={previewPropertyId}
      visitRequestStatus={requestStatusForProperty} onClose={closeQuickView} onRequestVisit={handleTenantRequestVisit} onViewProperty={onViewProperty}
      isFavorite={isPropertySaved(previewPropertyId)} favoriteStateReady={favoriteIsReadyFor(previewPropertyId)}
      favoritePending={isFavoritePending(previewPropertyId)} onToggleFavorite={toggleFavorite} />}
    </div>
  );
};
