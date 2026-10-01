import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight, BedDouble, CalendarDays, ChevronLeft, ChevronRight, Clock3, Heart, Home as HomeIcon, LoaderCircle, MapPin, RefreshCw, Search, X } from 'lucide-react';
import { Property, UserProfile } from '../types';
import { tenantVisitService, TenantVisitRequest } from '../services/tenantVisitService';
import { belongsToTenantVisitSession, isCurrentTenantVisitSession, readTenantVisitSession } from '../utils/tenantVisitSession';
import { appendUniqueVisitRequests, tenantVisitStatusLabel, tenantVisitView } from '../utils/tenantVisitView';
import { buildCloudinaryUrl } from '../utils/mediaTransform';
import { resetFiltersForManualCityChange, resetFiltersForSearchClear, RentalSearchFilters } from '../utils/rentalSearch';
import { CompactSearchContext } from './CompactSearchContext';
import { favoriteService } from '../services/favoriteService';
import { propertyService } from '../services/propertyService';
import { getMediaTagLabel } from '../utils/mediaTags';
import { applyPersistedSavedHomeChange, mergeSavedHomes, removeSavedHomesFromDiscovery } from '../utils/tenantSavedHomes';
import { mergeFavoriteLookupState, unresolvedFavoriteIds } from '../utils/favoriteLookup';

interface TenantDashboardProps {
  user: UserProfile;
  properties: Property[];
  discoveryState: 'LOADING' | 'READY' | 'ERROR';
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

const formatRequestedDate = (value: string | null | undefined): string | null => {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null :
    new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }).format(date);
};

const PropertyImage: React.FC<{ src?: string | null; alt: string }> = ({ src, alt }) => {
  const [failed, setFailed] = useState(false);
  const image = typeof src === 'string' ? src.trim() : '';
  useEffect(() => setFailed(false), [image]);
  return image && !failed ? (
    <img src={buildCloudinaryUrl(image, 'DISCOVERY_CARD')} alt={alt} loading="lazy"
      decoding="async" onError={() => setFailed(true)} className="h-full w-full object-cover motion-safe:transition-transform motion-safe:duration-300 group-hover:scale-[1.015] motion-reduce:group-hover:scale-100" />
  ) : <div className="flex h-full w-full items-center justify-center bg-[#e8e6df] text-slate-500" role="img" aria-label="Property photo unavailable"><HomeIcon size={36} aria-hidden="true" /></div>;
};

const focusClass = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700';
const readText = (value: unknown): string => typeof value === 'string' ? value.trim() : '';

const PropertyFacts: React.FC<{ property: Property }> = ({ property }) => (
  <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs font-semibold uppercase tracking-[0.12em] text-emerald-900">
    {property.bhk && <span className="inline-flex items-center gap-1"><BedDouble size={15} aria-hidden="true" />{property.bhk}</span>}
    {property.propertyType && <span>{property.propertyType.replace(/_/g, ' ').toLowerCase()}</span>}
  </div>
);

const PropertyLocation: React.FC<{ property: Property }> = ({ property }) => {
  const parts = [property.sector, property.city].filter((part): part is string => Boolean(part?.trim()));
  return parts.length ? <p className="mt-2 flex min-w-0 items-start gap-1.5 text-sm text-slate-600">
    <MapPin size={16} className="mt-0.5 shrink-0" aria-hidden="true" /><span className="min-w-0 break-words">{parts.join(', ')}</span>
  </p> : null;
};

const PropertyPrice: React.FC<{ property: Property }> = ({ property }) => {
  const isRent = property.listingType === 'RENT' && property.monthlyRent > 0;
  const isSale = property.listingType === 'SALE' && Number.isFinite(property.askingPrice) && (property.askingPrice ?? 0) > 0;
  if (!isRent && !isSale) return null;
  const amount = isRent ? property.monthlyRent : property.askingPrice!;
  return <p className="mt-2 text-lg font-semibold tracking-tight text-slate-950">
    ₹{amount.toLocaleString('en-IN')}{isRent && <span className="text-sm font-normal tracking-normal text-slate-600"> / month</span>}
  </p>;
};

const PropertyActions: React.FC<{ property: Property; onRequestVisit: (property: Property) => void; onOpenQuickView: (property: Property) => void }> = ({ property, onRequestVisit, onOpenQuickView }) => (
  <div className="mt-3 flex items-center justify-between gap-2 border-t border-slate-200/70 pt-2.5">
    <button type="button" onClick={() => onOpenQuickView(property)} className={`inline-flex min-h-11 items-center rounded-lg px-2 text-sm font-semibold text-emerald-900 transition-colors hover:bg-emerald-50 ${focusClass}`}>Quick view</button>
    <button type="button" onClick={() => onRequestVisit(property)} className={`min-h-11 rounded-xl bg-emerald-800 px-4 text-sm font-semibold text-white transition-colors hover:bg-emerald-900 motion-safe:active:scale-[0.99] ${focusClass}`}>Request visit</button>
  </div>
);

const SupportingPropertyCard: React.FC<{
  property: Property;
  onRequestVisit: (property: Property) => void;
  onOpenQuickView: (property: Property) => void;
  isFavorite: boolean;
  favoriteStateReady: boolean;
  favoritePending: boolean;
  onToggleFavorite: (property: Property) => void;
}> = ({ property, onRequestVisit, onOpenQuickView, isFavorite, favoriteStateReady, favoritePending, onToggleFavorite }) => (
  <article className="group min-w-0 overflow-hidden rounded-[18px] border border-[#e6e9e2] bg-white shadow-[0_8px_28px_-23px_rgba(15,45,34,.35)] transition-[transform,box-shadow] duration-200 motion-safe:hover:-translate-y-0.5 hover:shadow-[0_16px_34px_-25px_rgba(15,45,34,.4)]">
    <div className="relative aspect-[16/10] min-w-0 overflow-hidden bg-[#e8e6df]">
      <button type="button" id={`tenant-property-${property.id}`} onClick={() => onOpenQuickView(property)} aria-label={`Quick view: ${property.title || 'property'}`} className={`group/image block h-full w-full text-left ${focusClass}`}>
        <PropertyImage src={property.images?.[0]} alt={property.title ? `${property.title} photo` : 'Property photo'} />
      </button>
      {typeof property._mediaCount === 'number' && property._mediaCount > 0 && <span className="absolute bottom-3 left-3 rounded-full bg-slate-950/70 px-2.5 py-1.5 text-[10px] font-medium text-white">{property._mediaCount} media items</span>}
      <button type="button" onClick={() => onToggleFavorite(property)} disabled={!favoriteStateReady || favoritePending}
        aria-label={!favoriteStateReady ? `Saved state loading for ${property.title}` : favoritePending ? `${isFavorite ? 'Removing' : 'Saving'} ${property.title}` : isFavorite ? `Remove ${property.title} from saved properties` : `Save ${property.title}`}
        aria-pressed={favoriteStateReady ? isFavorite : undefined} title={isFavorite ? 'Remove from saved properties' : 'Save property'}
        className={`absolute right-3 top-3 flex h-11 w-11 items-center justify-center rounded-full border border-white/90 bg-white/90 text-emerald-900 shadow-sm backdrop-blur-[2px] transition-colors hover:bg-white disabled:cursor-wait disabled:opacity-75 ${focusClass}`}>
        {favoritePending ? <LoaderCircle size={16} className="motion-safe:animate-spin" aria-hidden="true" /> : <Heart size={17} fill={isFavorite ? 'currentColor' : 'none'} className={isFavorite ? 'text-emerald-800' : 'text-slate-700'} aria-hidden="true" />}
      </button>
    </div>
    <div className="p-3.5">
      <PropertyFacts property={property} />
      <h3 className="mt-1.5 break-words font-serif text-lg font-medium leading-snug tracking-tight text-slate-950"><button type="button" onClick={() => onOpenQuickView(property)} className={`text-left hover:text-emerald-800 ${focusClass}`}>{property.title?.trim() || 'Property'}</button></h3>
      <PropertyLocation property={property} />
      <PropertyPrice property={property} />
      <PropertyActions property={property} onRequestVisit={onRequestVisit} onOpenQuickView={onOpenQuickView} />
    </div>
  </article>
);

const SavedHomeCard: React.FC<{
  property: Property;
  isFavorite: boolean;
  favoriteStateReady: boolean;
  favoritePending: boolean;
  onToggleFavorite: (property: Property) => void;
  onOpenQuickView: (property: Property) => void;
  onRequestVisit: (property: Property) => void;
}> = ({ property, isFavorite, favoriteStateReady, favoritePending, onToggleFavorite, onOpenQuickView, onRequestVisit }) => (
  <article className="group flex w-[min(88vw,21rem)] shrink-0 snap-start overflow-hidden rounded-2xl border border-[#e6e9e2] bg-white shadow-[0_6px_24px_-21px_rgba(15,45,34,.36)] sm:w-[21rem]">
    <div className="relative aspect-square w-[36%] min-w-[6.5rem] shrink-0 overflow-hidden bg-[#e8e6df]">
      <button type="button" onClick={() => onOpenQuickView(property)} aria-label={`Quick view: ${property.title || 'property'}`} className={`block h-full w-full ${focusClass}`}>
        <PropertyImage src={property.images?.[0]} alt={property.title ? `${property.title} photo` : 'Property photo'} />
      </button>
      {typeof property._mediaCount === 'number' && property._mediaCount > 0 && <span className="absolute bottom-2 left-2 rounded-full bg-slate-950/70 px-2 py-1 text-[9px] font-medium text-white">{property._mediaCount} media items</span>}
      <button type="button" onClick={() => onToggleFavorite(property)} disabled={!favoriteStateReady || favoritePending}
        aria-label={favoritePending ? `Updating saved state for ${property.title || 'property'}` : isFavorite ? `Remove ${property.title || 'property'} from saved homes` : `Save ${property.title || 'property'}`}
        aria-pressed={favoriteStateReady ? isFavorite : undefined} title={isFavorite ? 'Remove from saved homes' : 'Save property'}
        className={`absolute right-2 top-2 flex h-11 w-11 items-center justify-center rounded-full border border-white/90 bg-white/90 text-emerald-900 shadow-sm backdrop-blur-[2px] hover:bg-white disabled:cursor-wait disabled:opacity-75 ${focusClass}`}>
        {favoritePending ? <LoaderCircle size={16} className="motion-safe:animate-spin" aria-hidden="true" /> : <Heart size={17} fill={isFavorite ? 'currentColor' : 'none'} className={isFavorite ? 'text-emerald-800' : 'text-slate-700'} aria-hidden="true" />}
      </button>
    </div>
    <div className="flex min-w-0 flex-1 flex-col p-3">
      <PropertyFacts property={property} />
      <h3 className="mt-1 line-clamp-2 min-h-[2.35rem] break-words font-serif text-[15px] font-medium leading-[1.2] tracking-tight text-slate-950">
        <button type="button" onClick={() => onOpenQuickView(property)} className={`text-left hover:text-emerald-800 ${focusClass}`}>{property.title?.trim() || 'Property'}</button>
      </h3>
      <p className="mt-1 truncate text-xs text-slate-600">{[property.sector, property.city].filter((part): part is string => Boolean(part?.trim())).join(', ')}</p>
      <PropertyPrice property={property} />
      <div className="mt-auto flex min-w-0 items-center justify-between gap-1 border-t border-slate-200/70 pt-2">
        <button type="button" onClick={() => onOpenQuickView(property)} className={`inline-flex min-h-11 shrink-0 items-center rounded-lg px-1.5 text-xs font-semibold text-emerald-900 hover:bg-emerald-50 ${focusClass}`}>Quick view</button>
        <button type="button" onClick={() => onRequestVisit(property)} className={`min-h-11 min-w-0 rounded-xl bg-emerald-800 px-2 text-xs font-semibold text-white hover:bg-emerald-900 ${focusClass}`}>Request visit</button>
      </div>
    </div>
  </article>
);

type QuickViewMedia = { url: string; type: 'IMAGE' | 'VIDEO'; tagLabel: string | null };

const TenantPropertyQuickView: React.FC<{
  propertyId: number;
  onClose: () => void;
  onRequestVisit: (property: Property) => void;
  onViewProperty: (property: Property) => void;
}> = ({ propertyId, onClose, onRequestVisit, onViewProperty }) => {
  const [property, setProperty] = useState<Property | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [activeMediaIndex, setActiveMediaIndex] = useState(0);
  const [failedMedia, setFailedMedia] = useState<Set<string>>(() => new Set());
  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const focusRestoreTimerRef = useRef<number | null>(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setProperty(null);
    propertyService.getPublicProperty(propertyId, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return;
        setProperty(result);
        setActiveMediaIndex(0);
        setFailedMedia(new Set());
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
    const focusTimer = window.setTimeout(() => closeButtonRef.current?.focus(), 0);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !dialogRef.current) return;
      const controls = [...dialogRef.current.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), video[controls], [tabindex]:not([tabindex="-1"])'
      )].filter(control => control.offsetParent !== null);
      if (controls.length === 0) { event.preventDefault(); dialogRef.current.focus(); return; }
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
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
  }, []);

  const media: QuickViewMedia[] = useMemo(() => {
    if (!property) return [];
    const stored = property.taggedMedia?.filter(item => item.mediaUrl?.trim()).map(item => ({
      url: item.mediaUrl,
      type: item.mediaType === 'VIDEO_WALKTHROUGH' || /\.(mp4|webm|mov)(?:[?#]|$)/i.test(item.mediaUrl) ? 'VIDEO' as const : 'IMAGE' as const,
      tagLabel: getMediaTagLabel(item.roomTag)
    })) || [];
    if (stored.length) return stored;
    return [
      ...(property.images || []).filter(Boolean).map(url => ({ url, type: 'IMAGE' as const, tagLabel: null })),
      ...(property.videoUrl ? [{ url: property.videoUrl, type: 'VIDEO' as const, tagLabel: null }] : [])
    ];
  }, [property]);
  const activeMedia = media[activeMediaIndex];
  const setRelativeMedia = (amount: -1 | 1) => setActiveMediaIndex(index => media.length ? (index + amount + media.length) % media.length : 0);
  const formatMoney = (value?: number | null) => typeof value === 'number' && value > 0 ? `₹${value.toLocaleString('en-IN')}` : null;
  const typeLabel = property?.propertyType?.replace(/_/g, ' ').toLowerCase();
  const amenities = property?.amenities?.split(',').map(value => value.trim()).filter(Boolean) || [];

  return (
    <div className="fixed inset-0 z-[110] flex justify-end" data-testid="tenant-property-quick-view">
      <div aria-hidden="true" onClick={onClose} className="absolute inset-0 bg-slate-950/45 backdrop-blur-[2px]" />
      <aside ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="tenant-quick-view-dialog-title" tabIndex={-1}
        className="relative z-10 flex h-full w-full min-w-0 flex-col bg-[#fffefa] shadow-2xl md:w-[min(56vw,760px)] md:border-l md:border-white/60">
        <header className="flex h-14 shrink-0 items-center justify-between border-b border-[#e5e9e1] px-4 sm:px-6">
          <h2 id="tenant-quick-view-dialog-title" className="text-[11px] font-extrabold uppercase tracking-[0.16em] text-emerald-800">Property quick view</h2>
          <button ref={closeButtonRef} type="button" onClick={onClose} aria-label="Close property quick view" className={`flex h-11 w-11 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-800 hover:bg-emerald-50 ${focusClass}`}><X size={18} aria-hidden="true" /></button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-5 pt-3 sm:px-6">
          {loading && <div role="status" aria-label="Loading property details" className="space-y-4"><div className="aspect-[16/10] animate-pulse rounded-2xl bg-[#e8eae3]" /><div className="h-4 w-28 animate-pulse rounded bg-emerald-100" /><div className="h-8 w-4/5 animate-pulse rounded bg-slate-200" /><div className="h-5 w-1/3 animate-pulse rounded bg-slate-100" /><div className="h-32 animate-pulse rounded-xl bg-slate-100" /></div>}
          {!loading && error && <div role="alert" className="rounded-2xl border border-rose-200 bg-white p-6 text-center"><p className="text-sm text-slate-700">{error}</p><button type="button" onClick={() => setRetry(value => value + 1)} className={`mt-4 min-h-11 rounded-xl bg-emerald-800 px-5 text-sm font-bold text-white hover:bg-emerald-900 ${focusClass}`}>Try again</button></div>}
          {!loading && !error && property && <>
            <div className="relative aspect-[16/10] overflow-hidden rounded-2xl bg-[#e7e8e1]" aria-label="Property gallery">
              {activeMedia && !failedMedia.has(activeMedia.url) ? activeMedia.type === 'VIDEO' ? <video key={activeMedia.url} controls playsInline preload="metadata" aria-label={activeMedia.tagLabel ? `${activeMedia.tagLabel} video` : 'Property video'} src={activeMedia.url} className="h-full w-full object-contain bg-slate-950" /> : <img key={activeMedia.url} src={buildCloudinaryUrl(activeMedia.url, 'DETAIL_MAIN') || activeMedia.url} alt={`${property.title}${activeMedia.tagLabel ? ` — ${activeMedia.tagLabel}` : ''}`} onError={() => setFailedMedia(previous => new Set(previous).add(activeMedia.url))} className="h-full w-full object-cover" /> : <div className="flex h-full flex-col items-center justify-center gap-2 text-slate-500" role="img" aria-label="Property media unavailable"><HomeIcon size={34} aria-hidden="true" /><span className="text-sm">Property media unavailable</span></div>}
              {activeMedia?.tagLabel && <span className="absolute left-3 top-3 rounded-full border border-white/70 bg-white/95 px-3 py-1.5 text-xs font-semibold text-emerald-950 shadow-sm">{activeMedia.tagLabel}</span>}
              {media.length > 1 && <>
                <button type="button" onClick={() => setRelativeMedia(-1)} aria-label="Previous property media" className={`absolute left-3 top-1/2 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full bg-white/95 text-slate-900 shadow-md ${focusClass}`}><ChevronLeft aria-hidden="true" /></button>
                <button type="button" onClick={() => setRelativeMedia(1)} aria-label="Next property media" className={`absolute right-3 top-1/2 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full bg-white/95 text-slate-900 shadow-md ${focusClass}`}><ChevronRight aria-hidden="true" /></button>
                <span className="absolute bottom-3 right-3 rounded-full bg-slate-950/80 px-3 py-1.5 text-xs font-semibold text-white">{activeMediaIndex + 1} / {media.length} media</span>
              </>}
            </div>
            {media.length > 1 && <div className="mt-2 flex gap-2 overflow-x-auto pb-1" aria-label="Property media thumbnails">{media.map((item, index) => <button type="button" key={`${item.url}-${index}`} onClick={() => setActiveMediaIndex(index)} aria-pressed={index === activeMediaIndex} aria-label={`Show ${item.tagLabel ? `${item.tagLabel} ` : ''}${item.type === 'VIDEO' ? 'video' : 'photo'} ${index + 1}`} className={`relative h-[68px] w-[90px] shrink-0 overflow-hidden rounded-lg border-2 bg-slate-100 ${index === activeMediaIndex ? 'border-emerald-700' : 'border-transparent'} ${focusClass}`}>
              {item.type === 'VIDEO' ? <span className="grid h-full place-items-center bg-slate-900 text-xs font-semibold text-white">Video</span> : failedMedia.has(item.url) ? <span className="grid h-full place-items-center bg-slate-200 text-[9px] font-semibold text-slate-700">Unavailable</span> : <img loading="lazy" src={buildCloudinaryUrl(item.url, 'DETAIL_THUMBNAIL') || item.url} alt="" onError={() => setFailedMedia(previous => new Set(previous).add(item.url))} className="h-full w-full object-cover" />}
              {item.tagLabel && <span className="absolute inset-x-0 bottom-0 truncate bg-slate-950/75 px-1 py-0.5 text-[9px] font-semibold text-white">{item.tagLabel}</span>}
            </button>)}</div>}
            <div className="mt-5">
              <p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-emerald-800">{property.bhk || ''}{property.bhk && typeLabel ? ' · ' : ''}{typeLabel || ''}</p>
              <h1 className="mt-2 font-serif text-[clamp(1.45rem,3vw,2rem)] font-medium leading-tight tracking-tight text-slate-950">{property.title}</h1>
              <p className="mt-2 flex items-start gap-1.5 text-sm text-slate-600"><MapPin size={16} className="mt-0.5 shrink-0 text-emerald-800" aria-hidden="true" />{[property.sector, property.city].filter(Boolean).join(', ')}</p>
              {formatMoney(property.monthlyRent) && <p className="mt-3 text-2xl font-bold tracking-tight text-slate-950">{formatMoney(property.monthlyRent)} <span className="text-sm font-normal text-slate-600">/ month</span></p>}
              <div className="mt-3 flex flex-wrap gap-2">{property.bhk && <span className="rounded-md bg-[#edf5ee] px-2.5 py-1.5 text-xs font-semibold text-emerald-950">{property.bhk}</span>}{typeLabel && <span className="rounded-md bg-[#edf5ee] px-2.5 py-1.5 text-xs font-semibold capitalize text-emerald-950">{typeLabel}</span>}{property.totalAreaSqFt > 0 && <span className="rounded-md bg-[#edf5ee] px-2.5 py-1.5 text-xs font-semibold text-emerald-950">{property.totalAreaSqFt.toLocaleString('en-IN')} sq ft</span>}{property.furnishingStatus && <span className="rounded-md bg-[#edf5ee] px-2.5 py-1.5 text-xs font-semibold text-emerald-950">{property.furnishingStatus}</span>}</div>
              {property.description && <p className="mt-4 whitespace-pre-line text-sm leading-6 text-slate-600">{property.description}</p>}
              <dl className="mt-4 divide-y divide-slate-200 border-y border-slate-200 text-sm">{formatMoney(property.securityDeposit) && <div className="flex justify-between gap-4 py-3"><dt className="text-slate-600">Security deposit</dt><dd className="font-semibold text-slate-900">{formatMoney(property.securityDeposit)}</dd></div>}{formatMoney(property.maintenanceCharge) && <div className="flex justify-between gap-4 py-3"><dt className="text-slate-600">Maintenance</dt><dd className="font-semibold text-slate-900">{formatMoney(property.maintenanceCharge)} / month</dd></div>}{property.bathroomCount ? <div className="flex justify-between gap-4 py-3"><dt className="text-slate-600">Bathrooms</dt><dd className="font-semibold text-slate-900">{property.bathroomCount}</dd></div> : null}{property.vastuFacing && <div className="flex justify-between gap-4 py-3"><dt className="text-slate-600">Facing</dt><dd className="font-semibold text-slate-900">{property.vastuFacing}</dd></div>}</dl>
              {amenities.length > 0 && <div className="mt-4"><h2 className="font-serif text-lg">Listed amenities</h2><div className="mt-2 flex flex-wrap gap-2">{amenities.map(amenity => <span key={amenity} className="rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-medium text-emerald-950">{amenity}</span>)}</div></div>}
            </div>
          </>}
        </div>
        {!loading && !error && property && <footer className="safe-area-bottom flex shrink-0 gap-2 border-t border-[#e5e9e1] bg-white/95 p-3 backdrop-blur sm:px-6">
          <button type="button" onClick={() => onRequestVisit(property)} className={`min-h-12 flex-1 rounded-xl bg-emerald-800 px-3 text-sm font-bold text-white hover:bg-emerald-900 ${focusClass}`}>Request visit <ArrowRight size={15} className="ml-1 inline" aria-hidden="true" /></button>
          <button type="button" onClick={() => onViewProperty(property)} className={`min-h-12 flex-1 rounded-xl border border-emerald-800/25 bg-white px-3 text-sm font-bold text-emerald-900 hover:bg-emerald-50 ${focusClass}`}>View full property</button>
        </footer>}
      </aside>
    </div>
  );
};

type FavoriteState = {
  identityKey: string | null;
  status: 'loading' | 'ready' | 'error';
  lookupError: boolean;
  propertyIds: Set<number>;
  favoriteIds: Set<number>;
};

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
  user, properties, discoveryState, discoveryCity, searchFilters, hasMoreProperties,
  loadingMoreProperties, loadMorePropertiesError, onSearchHomes, onRetryDiscovery,
  onLoadMoreProperties, onRequestVisit, onViewProperty
}) => {
  const [history, setHistory] = useState<HistoryState>(emptyHistory);
  const [loadMorePending, setLoadMorePending] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState(false);
  const [reload, setReload] = useState(0);
  const pageRequestRef = useRef<AbortController | null>(null);
  const pagePendingRef = useRef(false);
  const location = useLocation();
  const navigate = useNavigate();
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
  const savedHomesRequestRef = useRef<AbortController | null>(null);
  const savedHomesLoadingMoreRef = useRef(false);
  const favoriteMutationRef = useRef<Set<string>>(new Set());
  const favoriteMutationRevisionRef = useRef<Map<string, number>>(new Map());
  const favoriteSession = readTenantVisitSession(user.id);
  const closeQuickView = useCallback(() => navigate(-1), [navigate]);
  const openQuickView = useCallback((property: Property) => {
    const prior = location.state && typeof location.state === 'object' && !Array.isArray(location.state)
      ? location.state as Record<string, unknown> : {};
    navigate(`${location.pathname}${location.search}${location.hash}`, {
      state: { ...prior, tenantQuickView: true, tenantQuickViewPropertyId: property.id, tenantQuickViewOpenerId: `tenant-property-${property.id}` }
    });
  }, [location.hash, location.pathname, location.search, location.state, navigate]);

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
        const merged = mergeFavoriteLookupState(currentState.propertyIds, currentState.favoriteIds, unresolvedIds, savedIds);
        return { ...currentState, status: 'ready', lookupError: false,
          propertyIds: merged.resolvedIds, favoriteIds: merged.favoriteIds };
      });
    }).catch(() => {
      if (controller.signal.aborted || !isCurrentTenantVisitSession(session)) return;
      setFavoriteState(currentState => currentState.identityKey === session.key
        ? { ...currentState, status: currentState.propertyIds.size > 0 ? 'ready' : 'error', lookupError: true }
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
      favoriteMutationRevisionRef.current.set(session.key,
        (favoriteMutationRevisionRef.current.get(session.key) || 0) + 1);
      setFavoriteState(current => {
        if (current.identityKey !== session.key) return current;
        const next = new Set(current.favoriteIds);
        if (wasSaved) next.delete(property.id); else next.add(property.id);
        const propertyIds = new Set(current.propertyIds).add(property.id);
        return { ...current, status: 'ready', lookupError: false, propertyIds, favoriteIds: next };
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
    const refresh = () => setReload(value => value + 1);
    window.addEventListener('pathome_auth_changed', refresh);
    window.addEventListener('pathome_visit_request_created', refresh);
    window.addEventListener('storage', refresh);
    return () => {
      window.removeEventListener('pathome_auth_changed', refresh);
      window.removeEventListener('pathome_visit_request_created', refresh);
      window.removeEventListener('storage', refresh);
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

  const session = readTenantVisitSession(user.id);
  const visibleHistory: HistoryState = !session ? { ...emptyHistory, status: 'error' } :
    session.key === history.identityKey ? history : emptyHistory;
  const view = tenantVisitView(visibleHistory.status, visibleHistory.requests);
  const firstName = user.fullName?.trim().split(/\s+/)[0];
  const savedHomesIdentityMatches = Boolean(favoriteSession && savedHomesState.identityKey === favoriteSession.key);
  const visibleSavedHomes = savedHomesIdentityMatches && savedHomesState.status !== 'error'
    ? savedHomesState.properties : [];
  const visibleFavoriteIds = new Set<number>([
    ...(favoriteSession && favoriteState.identityKey === favoriteSession.key && favoriteState.status === 'ready'
      ? favoriteState.favoriteIds : []),
    ...visibleSavedHomes.map(property => property.id)
  ]);
  const availableProperties = removeSavedHomesFromDiscovery(properties, visibleFavoriteIds);
  const favoriteIsReadyFor = (propertyId: number): boolean => Boolean(favoriteSession && (
    favoriteState.identityKey === favoriteSession.key && favoriteState.status === 'ready'
      && favoriteState.propertyIds.has(propertyId)
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
    <>
    <main aria-hidden={previewPropertyId !== null} className="min-w-0 bg-[#f7f7f2] pb-16 text-slate-950" style={{ backgroundImage: 'radial-gradient(ellipse at 8% 20%, rgba(207, 219, 198, 0.22), transparent 34%), radial-gradient(ellipse at 96% 48%, rgba(230, 223, 202, 0.2), transparent 30%)' }}>
      <div className="mx-auto w-full max-w-7xl min-w-0 px-4 pt-5 sm:px-6 sm:pt-7 lg:px-8 lg:pt-8">
        <div id="tenant-home-search">
          <header className="relative z-40 isolate grid min-h-[21rem] min-w-0 gap-x-6 gap-y-5 overflow-visible rounded-[26px] bg-[#0b3028] px-5 py-6 text-white shadow-[0_24px_56px_-42px_rgba(8,49,39,.72)] sm:min-h-[22rem] sm:rounded-[30px] sm:px-7 sm:py-7 lg:min-h-[15rem] lg:grid-cols-[minmax(0,0.82fr)_minmax(0,1.18fr)] lg:items-center lg:px-8 lg:py-7 xl:px-9">
            <img src="/assets/interior_living.jpg" alt="" aria-hidden="true" fetchPriority="high" decoding="async" className="pointer-events-none absolute inset-0 -z-20 h-full w-full rounded-[inherit] object-cover object-[62%_48%]" />
            <div className="pointer-events-none absolute inset-0 -z-10 rounded-[inherit] bg-[linear-gradient(104deg,rgba(5,34,27,.93)_0%,rgba(7,44,35,.82)_42%,rgba(7,43,35,.62)_100%)]" aria-hidden="true" />
            <div className="relative z-10 max-w-3xl lg:max-w-xl">
              <p className="text-xs font-bold uppercase tracking-[0.2em] text-emerald-200">Your home search</p>
              <h1 className="mt-1.5 break-words font-serif text-[clamp(2rem,6vw,3rem)] font-medium leading-[1.04] tracking-tight lg:text-[clamp(2rem,3vw,2.5rem)]">
                {firstName ? `Find your place, ${firstName}.` : 'Find your place.'}
              </h1>
              <p className="mt-1.5 max-w-xl text-sm leading-5 text-emerald-50/85 lg:max-w-md">Explore homes that fit and keep your visit requests in one place.</p>
            </div>
            <CompactSearchContext
              appearance="hero"
              alwaysEditing
              searchPlaceholder="Search locality or describe your home"
              submitLabel="Explore homes"
              city={discoveryCity}
              filters={searchFilters}
              onSearch={handleSmartSearch}
              onManualCityChange={city => applySmartSearch(resetFiltersForManualCityChange(city))}
              onClearAll={() => applySmartSearch(resetFiltersForSearchClear(discoveryCity))}
            />
          </header>
        </div>

        <div className="mt-6 grid min-w-0 gap-7 sm:mt-7 lg:grid-cols-[minmax(13.5rem,0.26fr)_minmax(0,0.74fr)] lg:items-start lg:gap-6 xl:gap-7">
          <section id="visit-history" aria-labelledby="visit-history-title" className={`${view === 'populated' ? 'order-1' : 'order-2'} min-w-0 scroll-mt-24 lg:order-1`}>
            <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
              <div><p className="text-xs font-bold uppercase tracking-[0.16em] text-emerald-800">Your journey</p>
                <h2 id="visit-history-title" className="mt-1 font-['Outfit'] text-2xl font-semibold tracking-tight">Visit requests</h2>
                <p className="mt-1 text-sm text-slate-600">Requests you have sent for homes you want to see.</p>
              </div>
              {view === 'populated' && <p className="text-sm font-medium text-slate-600" aria-label={`${visibleHistory.totalCount} ${visibleHistory.totalCount === 1 ? 'visit request' : 'visit requests'} sent`}>
                {visibleHistory.totalCount} {visibleHistory.totalCount === 1 ? 'request' : 'requests'} sent
              </p>}
            </div>

            {view === 'loading' && <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-1" role="status" aria-live="polite" aria-label="Loading your visit requests">
              {[0, 1].map(index => <div key={index} className="overflow-hidden rounded-[24px] border border-slate-200 bg-white shadow-sm sm:flex sm:h-48">
                <div className="aspect-[16/9] bg-slate-200 motion-safe:animate-pulse sm:aspect-auto sm:w-[38%] sm:shrink-0" /><div className="p-5 sm:flex-1"><div className="h-4 w-24 rounded bg-slate-200 motion-safe:animate-pulse" /><div className="mt-5 h-5 w-4/5 rounded bg-slate-200 motion-safe:animate-pulse" /><div className="mt-3 h-4 w-1/2 rounded bg-slate-100 motion-safe:animate-pulse" /></div>
              </div>)}<span className="sr-only">Loading your visit requests</span>
            </div>}

            {view === 'error' && <div className="rounded-[28px] border border-rose-200 bg-white px-5 py-8 shadow-sm sm:px-8" role="alert">
              <h3 className="font-['Outfit'] text-xl font-semibold">Your requests are unavailable right now</h3>
              <p className="mt-2 text-sm leading-6 text-slate-600">Please try again. You can still explore available homes below.</p>
              <button type="button" onClick={() => setReload(value => value + 1)} className={`mt-5 inline-flex min-h-11 items-center gap-2 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 ${focusClass}`}><RefreshCw size={16} aria-hidden="true" /> Retry</button>
            </div>}

            {view === 'empty' && <div className="relative isolate grid gap-3 overflow-hidden rounded-[18px] border border-[#e5e8e1] bg-white p-4 shadow-[0_8px_24px_-22px_rgba(15,45,34,.35)] sm:grid-cols-[auto_1fr] sm:items-center lg:grid-cols-1 lg:justify-items-start" role="status" aria-live="polite">
              <div className="relative flex h-12 w-12 items-center justify-center rounded-2xl border border-emerald-100 bg-[#eff5ef] text-emerald-800">
                <CalendarDays size={23} aria-hidden="true" />
                <span className="absolute -bottom-1.5 -right-1.5 flex h-7 w-7 items-center justify-center rounded-xl border-2 border-white bg-white text-emerald-800 shadow-sm"><Clock3 size={14} aria-hidden="true" /></span>
              </div>
              <div className="relative min-w-0"><h3 className="font-['Outfit'] text-base font-semibold tracking-tight">You haven’t requested a visit yet.</h3>
                <p className="mt-1.5 text-sm leading-5 text-slate-600">Explore available homes and request a visit when one feels right.</p>
                <a href="#discover-homes" className={`mt-2 inline-flex min-h-11 items-center gap-2 rounded-lg px-2 text-sm font-semibold text-emerald-800 transition-colors hover:bg-emerald-50 ${focusClass}`}>Explore homes <ArrowRight size={16} aria-hidden="true" /></a>
              </div>
            </div>}

            {view === 'populated' && <>
              <div className="grid min-w-0 gap-4 lg:grid-cols-1">
                {visibleHistory.requests.map(request => {
                  const date = formatRequestedDate(request.requestedAt);
                  const location = [readText(request.sector), readText(request.city)].filter(Boolean).join(', ');
                  const title = readText(request.propertyTitle);
                  const bhk = readText(request.bhk);
                  const propertyType = readText(request.propertyType);
                  const preferredTiming = readText(request.preferredVisitTiming);
                  return <article key={request.requestId} className="group min-w-0 overflow-hidden rounded-[22px] border border-[#e1e5dc] bg-white shadow-[0_12px_32px_-28px_rgba(15,45,34,.5)] transition-[transform,box-shadow] duration-200 motion-safe:hover:-translate-y-0.5 hover:shadow-[0_20px_38px_-28px_rgba(15,45,34,.48)] sm:flex lg:block">
                    <div className="aspect-[16/9] min-w-0 bg-[#e8e6df] sm:aspect-auto sm:w-[38%] sm:shrink-0 lg:aspect-[16/9] lg:w-full"><PropertyImage src={request.coverImageUrl} alt={title ? `${title} photo` : 'Property photo'} /></div>
                    <div className="flex min-w-0 flex-1 flex-col p-5">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={`rounded-full border px-3 py-1.5 text-xs font-semibold ${request.status === 'RECEIVED' ? 'border-emerald-200 bg-emerald-50 text-emerald-900' : 'border-slate-200 bg-slate-100 text-slate-800'}`}>{tenantVisitStatusLabel(request.status)}</span>
                        {date && <span className="text-xs text-slate-600">Requested {date}</span>}
                      </div>
                      <h3 className="mt-3 break-words font-serif text-lg font-medium leading-snug">{title || 'Property'}</h3>
                      {(bhk || propertyType) && <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs font-semibold uppercase tracking-wide text-emerald-800">{bhk && <span className="inline-flex items-center gap-1"><BedDouble size={15} aria-hidden="true" />{bhk}</span>}{propertyType && <span>{propertyType.replace(/_/g, ' ').toLowerCase()}</span>}</p>}
                      {location && <p className="mt-2 flex min-w-0 items-start gap-1.5 text-sm text-slate-600"><MapPin size={16} className="mt-0.5 shrink-0" aria-hidden="true" /><span className="min-w-0 break-words">{location}</span></p>}
                      {preferredTiming && <p className="mt-2 flex min-w-0 items-start gap-1.5 text-sm text-slate-600"><Clock3 size={16} className="mt-0.5 shrink-0" aria-hidden="true" /><span className="min-w-0 break-words">Preferred: {preferredTiming}</span></p>}
                      <div className="mt-auto pt-4">{request.propertyAvailable && Number.isSafeInteger(request.propertyId) && request.propertyId > 0 ? <Link to={`/property/${request.propertyId}`} className={`inline-flex min-h-11 items-center gap-2 text-sm font-semibold text-emerald-800 underline-offset-4 hover:underline ${focusClass}`}>View property <ArrowRight size={16} aria-hidden="true" /></Link> : <p className="text-sm text-slate-600">This property is no longer available to view.</p>}</div>
                    </div>
                  </article>;
                })}
              </div>
              {visibleHistory.hasMore && <div className="mt-6 text-center"><button type="button" onClick={loadMore} disabled={loadMorePending} className={`min-h-11 rounded-xl border border-slate-300 bg-white px-6 text-sm font-semibold text-slate-800 transition-colors hover:border-emerald-700 hover:bg-emerald-50 disabled:opacity-60 ${focusClass}`}>{loadMorePending ? <><RefreshCw size={15} className="mr-2 inline motion-safe:animate-spin" aria-hidden="true" />Loading more…</> : 'Load more requests'}</button>
                {loadMoreError && <p role="alert" className="mt-2 text-sm text-rose-700">Could not load more requests. Please try again.</p>}</div>}
            </>}
          </section>

          <section id="discover-homes" aria-labelledby="discover-homes-title" className={`${view === 'populated' ? 'order-2' : 'order-1'} min-w-0 scroll-mt-24 lg:order-2`}>
            <section aria-labelledby="saved-homes-title" className="mb-7 min-w-0">
              <div className="mb-3 flex flex-wrap items-end justify-between gap-x-4 gap-y-1">
                <div><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-emerald-800">Keep close</p>
                  <h2 id="saved-homes-title" className="mt-0.5 font-serif text-xl font-medium tracking-tight">Saved homes</h2>
                </div>
                {visibleSavedHomes.length > 0 && <p className="text-xs text-slate-500">Homes you’ve saved</p>}
              </div>

              {savedHomesIdentityMatches && savedHomesState.status === 'error' && <div className="mb-3 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50/90 px-3 py-2.5" role="alert">
                <p className="text-sm text-amber-950">Saved homes couldn’t be loaded right now.</p>
                <button type="button" onClick={() => setSavedHomesReload(value => value + 1)} className={`min-h-11 rounded-lg px-3 text-sm font-semibold text-emerald-900 underline underline-offset-2 ${focusClass}`}>Retry</button>
              </div>}

              {savedHomesIdentityMatches && savedHomesState.status === 'loading' && visibleSavedHomes.length === 0 && <div className="flex gap-3 overflow-hidden pb-1" role="status" aria-label="Loading saved homes">
                {[0, 1].map(index => <div key={index} className="h-32 w-[min(88vw,21rem)] shrink-0 animate-pulse rounded-2xl border border-slate-200 bg-white" />)}
              </div>}

              {savedHomesIdentityMatches && savedHomesState.status === 'ready' && visibleSavedHomes.length === 0 && <div className="flex min-h-[4.25rem] items-center gap-3 rounded-2xl border border-[#e7eae3] bg-white/70 px-4 py-3">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[#edf4ee] text-emerald-800"><Heart size={19} aria-hidden="true" /></span>
                <div className="min-w-0"><p className="text-sm font-semibold text-slate-900">Save a home for later</p><p className="mt-0.5 text-sm text-slate-600">Heart homes you like and they’ll appear here.</p></div>
              </div>}

              {visibleSavedHomes.length > 0 && <div className="flex snap-x gap-3 overflow-x-auto overscroll-x-contain pb-2 pr-1" aria-label="Your saved homes">
                {visibleSavedHomes.map(property => <SavedHomeCard key={property.id} property={property}
                  isFavorite={isPropertySaved(property.id)} favoriteStateReady={favoriteIsReadyFor(property.id)}
                  favoritePending={isFavoritePending(property.id)} onToggleFavorite={toggleFavorite}
                  onOpenQuickView={openQuickView} onRequestVisit={onRequestVisit} />)}
              </div>}

              {savedHomesIdentityMatches && savedHomesState.status === 'ready' && savedHomesState.hasMore && <div className="mt-1 text-right">
                <button type="button" onClick={loadMoreSavedHomes} disabled={savedHomesState.loadingMore} className={`inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-sm font-semibold text-emerald-900 hover:bg-emerald-50 disabled:opacity-60 ${focusClass}`}>
                  {savedHomesState.loadingMore ? <><RefreshCw size={15} className="motion-safe:animate-spin" aria-hidden="true" />Loading saved homes…</> : 'Show more saved homes'}
                </button>
              </div>}
            </section>

            <div className="mb-4"><p className="text-xs font-bold uppercase tracking-[0.16em] text-emerald-800">Keep exploring</p>
              <h2 id="discover-homes-title" className="mt-1 font-serif text-2xl font-medium tracking-tight">Available homes</h2>
              <p className="mt-1 text-sm text-slate-600">Browse current listings and request a visit when you find a fit.</p>
            </div>
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
            {discoveryState === 'READY' && properties.length > 0 && availableProperties.length === 0 && <p className="rounded-2xl border border-[#e7eae3] bg-white/70 px-4 py-3 text-sm text-slate-600">Homes you’ve saved from this search are shown above.</p>}
            {discoveryState === 'READY' && availableProperties.length > 0 && <div className="grid min-w-0 gap-4 sm:grid-cols-2">
              {availableProperties.map(property => {
                const favoriteStateReady = favoriteIsReadyFor(property.id);
                return <SupportingPropertyCard key={property.id} property={property} onRequestVisit={onRequestVisit}
                  onOpenQuickView={openQuickView} isFavorite={isPropertySaved(property.id)}
                  favoriteStateReady={favoriteStateReady} favoritePending={isFavoritePending(property.id)}
                  onToggleFavorite={toggleFavorite} />;
              })}
            </div>}
            {discoveryState === 'READY' && hasMoreProperties && <div className="mt-7 text-center"><button type="button" onClick={onLoadMoreProperties} disabled={loadingMoreProperties} className={`inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 bg-white px-6 text-sm font-semibold text-slate-800 transition-colors hover:border-emerald-700 hover:bg-emerald-50 disabled:opacity-60 ${focusClass}`}>{loadingMoreProperties ? <><RefreshCw size={15} className="mr-2 motion-safe:animate-spin" aria-hidden="true" />Loading more homes…</> : 'Load more homes'}</button>{loadMorePropertiesError && <p role="alert" className="mt-2 text-sm text-rose-700">Could not load more homes. Please try again.</p>}</div>}
          </section>
        </div>
      </div>
    </main>
    {previewPropertyId !== null && <TenantPropertyQuickView key={previewPropertyId} propertyId={previewPropertyId}
      onClose={closeQuickView} onRequestVisit={onRequestVisit} onViewProperty={onViewProperty} />}
    </>
  );
};
