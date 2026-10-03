import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight, BedDouble, Building2, CalendarDays, Camera, ChevronLeft, ChevronRight, Clock3, Heart, Home as HomeIcon, LoaderCircle, MapPin, RefreshCw, Search, SlidersHorizontal, X } from 'lucide-react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { Property, UserProfile } from '../types';
import { tenantVisitService, TenantVisitRequest } from '../services/tenantVisitService';
import { createVisitOperationId, TenantVisitOutcome, TenantVisitStartCode, VisitExecutionView, visitExecutionService } from '../services/visitExecutionService';
import { belongsToTenantVisitSession, isCurrentTenantVisitSession, readTenantVisitSession } from '../utils/tenantVisitSession';
import { appendUniqueVisitRequests, tenantVisitStatusLabel, tenantVisitSummary, tenantVisitView } from '../utils/tenantVisitView';
import { buildCloudinaryUrl } from '../utils/mediaTransform';
import { resetFiltersForManualCityChange, resetFiltersForSearchClear, RentalPropertyType, RentalSearchFilters, discoverySearchKey } from '../utils/rentalSearch';
import { queueTenantHeroFilterScroll, resolveTenantHeroFilterScroll, type PendingTenantHeroFilterScroll } from '../utils/tenantQuickFilterScroll';
import { CompactSearchContext } from './CompactSearchContext';
import { favoriteService } from '../services/favoriteService';
import { propertyService } from '../services/propertyService';
import { getMediaTagLabel } from '../utils/mediaTags';
import { applyPersistedSavedHomeChange, mergeSavedHomes, removeSavedHomesFromDiscovery, savedHomesForPresentation, savedHomesVisibleLimitForWidth } from '../utils/tenantSavedHomes';
import { formatPropertyArea, formatSecurityDeposit } from '../utils/discoveryCardData';
import { tenantPropertyTypeLabel } from '../utils/tenantPropertyTypeLabel';
import { TenantQuickRefineMobile, TenantQuickRefinePanel } from './TenantQuickRefine';
import { quickRefineRentBounds as getQuickRefineRentBounds } from '../utils/tenantQuickRefine';
import type { QuickRefineRentBounds } from '../utils/tenantQuickRefine';
import { shouldRenderTenantMobileDock, tenantMobileDockBadges, tenantMobileDockTarget } from '../utils/tenantMobileDock';
import type { TenantMobileDockItem } from '../utils/tenantMobileDock';
import { createTenantSearchMorphOverlay, shouldCollapseTenantSearch, tenantSearchCollisionBand, tenantSearchCollisionReached, tenantSearchMorphPlan } from '../utils/tenantSearchMorph';
import { tenantPropertyOutcomeLabel, tenantVisitOutcomeStatusLabel, tenantVisitOutcomeSummaryText } from '../utils/tenantVisitOutcomePresentation';
import type { TenantSearchMorphOverlay } from '../utils/tenantSearchMorph';
import { LastUpdatedMeta } from './LastUpdatedMeta';
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

const PropertyImage: React.FC<{ src?: string | null; alt: string; editorialHover?: boolean; premiumCardHover?: boolean }> = ({ src, alt, editorialHover = false, premiumCardHover = false }) => {
  const [failed, setFailed] = useState(false);
  const image = typeof src === 'string' ? src.trim() : '';
  useEffect(() => setFailed(false), [image]);
  return image && !failed ? (
    <img src={buildCloudinaryUrl(image, 'DISCOVERY_CARD')} alt={alt} loading="lazy"
      decoding="async" onError={() => setFailed(true)} className={`h-full w-full object-cover ${premiumCardHover
        ? 'transition-transform duration-[320ms] ease-[cubic-bezier(.16,1,.3,1)] group-hover/available-card:scale-[1.035] group-hover/available-card:-translate-y-0.5 motion-reduce:transform-none motion-reduce:transition-none'
        : editorialHover
        ? 'transition-transform duration-300 ease-[cubic-bezier(.16,1,.3,1)] group-hover:scale-[1.025] group-hover:-translate-y-0.5 motion-reduce:transform-none motion-reduce:transition-none'
        : 'motion-safe:transition-transform motion-safe:duration-300 group-hover:scale-[1.015] motion-reduce:group-hover:scale-100'}`} />
  ) : <div className="flex h-full w-full items-center justify-center bg-[#e8e6df] text-slate-500" role="img" aria-label="Property photo unavailable"><HomeIcon size={36} aria-hidden="true" /></div>;
};

const focusClass = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700';
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

const PropertyFacts: React.FC<{ property: Property }> = ({ property }) => (
  <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs font-semibold uppercase tracking-[0.12em] text-emerald-900">
    {property.bhk && <span className="inline-flex items-center gap-1"><BedDouble size={15} aria-hidden="true" />{property.bhk}</span>}
    {property.propertyType && <span>{property.propertyType.replace(/_/g, ' ').toLowerCase()}</span>}
  </div>
);

const PropertyLocation: React.FC<{ property: Property; compact?: boolean }> = ({ property, compact = false }) => {
  const parts = [property.sector, property.city].filter((part): part is string => Boolean(part?.trim()));
  if (!parts.length) return compact ? <p className="min-h-5" aria-hidden="true">&nbsp;</p> : null;
  return <p className={`flex min-w-0 items-start gap-1.5 text-slate-600 ${compact ? 'text-xs leading-5' : 'mt-2 text-sm'}`}>
    <MapPin size={compact ? 14 : 16} className="mt-0.5 shrink-0" aria-hidden="true" /><span className="min-w-0 break-words">{parts.join(', ')}</span>
  </p>;
};

const PropertyPrice: React.FC<{ property: Property; compact?: boolean }> = ({ property, compact = false }) => {
  const isRent = property.listingType === 'RENT' && property.monthlyRent > 0;
  const isSale = property.listingType === 'SALE' && Number.isFinite(property.askingPrice) && (property.askingPrice ?? 0) > 0;
  if (!isRent && !isSale) return null;
  const amount = isRent ? property.monthlyRent : property.askingPrice!;
  return <p className={`${compact ? 'mt-1 text-[15px]' : 'mt-2 text-lg'} font-semibold tracking-tight text-slate-950 transition-transform duration-300 group-hover:-translate-y-px motion-reduce:transform-none`}>
    ₹{amount.toLocaleString('en-IN')}{isRent && <span className={`${compact ? 'text-[10px]' : 'text-sm'} font-normal tracking-normal text-slate-600`}> / month</span>}
  </p>;
};

const SupportingPropertyCard: React.FC<{
  property: Property;
  onRequestVisit: (property: Property) => void;
  onOpenQuickView: (property: Property) => void;
  isFavorite: boolean;
  favoriteStateReady: boolean;
  favoritePending: boolean;
  onToggleFavorite: (property: Property) => void;
  revealIndex: number;
  reduceMotion: boolean;
}> = ({ property, onRequestVisit, onOpenQuickView, isFavorite, favoriteStateReady, favoritePending, onToggleFavorite, revealIndex, reduceMotion }) => {
  const isRent = property.listingType === 'RENT' && property.monthlyRent > 0;
  const isSale = property.listingType === 'SALE' && Number.isFinite(property.askingPrice) && (property.askingPrice ?? 0) > 0;
  const amount = isRent ? property.monthlyRent : isSale ? property.askingPrice! : null;
  const priceLabel = property.listingType === 'RENT' ? 'Monthly rent' : property.listingType === 'SALE' ? 'Asking price' : null;
  const location = [property.sector, property.city].filter((part): part is string => Boolean(part?.trim())).join(', ');
  const area = formatPropertyArea(property.totalAreaSqFt);
  const bhk = formatTenantCardBhk(property.bhk);
  const furnishing = formatTenantCardFurnishing(property.furnishingStatus);
  const propertyType = tenantPropertyTypeLabel(property.propertyType);
  const deposit = isRent && typeof property.securityDeposit === 'number'
    && Number.isFinite(property.securityDeposit) && property.securityDeposit >= 0
    ? formatSecurityDeposit(property.securityDeposit)
    : null;
  return <motion.article initial={reduceMotion || typeof IntersectionObserver === 'undefined' ? false : 'hidden'}
    whileInView="visible"
    whileHover={reduceMotion ? undefined : { y: -6, transition: { duration: 0.32, ease: [0.16, 1, 0.3, 1] } }}
    variants={tenantCardRevealVariants}
    viewport={{ once: true, amount: 0.12 }}
    transition={{ duration: reduceMotion ? 0 : 0.56, delay: reduceMotion ? 0 : (revealIndex % 2) * 0.08, ease: [0.16, 1, 0.3, 1] }}
    className="group/available-card relative flex h-full min-w-0 flex-col pb-1 motion-reduce:transform-none">
    <div className="group/image relative z-0 aspect-[16/10] min-w-0 overflow-hidden rounded-[24px] bg-[#e8e6df] shadow-[0_12px_28px_-20px_rgba(15,45,34,.4)]">
      <button type="button" id={`tenant-property-${property.id}`} onClick={() => onOpenQuickView(property)} aria-label={`Quick view: ${property.title || 'property'}`} className={`relative block h-full w-full text-left ${focusClass}`}>
        <motion.div variants={tenantCardImageRevealVariants}
          transition={{ duration: reduceMotion ? 0 : 0.64, delay: reduceMotion ? 0 : (revealIndex % 2) * 0.08, ease: [0.16, 1, 0.3, 1] }}
          className="absolute inset-0">
          <PropertyImage src={property.images?.[0]} alt={property.title ? `${property.title} photo` : 'Property photo'} premiumCardHover />
        </motion.div>
      </button>
      <span aria-hidden="true" className="pointer-events-none absolute inset-x-0 bottom-0 h-12 -translate-x-[115%] skew-x-[-18deg] bg-[linear-gradient(110deg,transparent_18%,rgba(167,243,208,.14)_48%,transparent_78%)] transition-transform duration-[620ms] ease-[cubic-bezier(.16,1,.3,1)] group-hover/image:translate-x-[115%] motion-reduce:hidden" />
      {typeof property._mediaCount === 'number' && property._mediaCount > 0 && <span className="absolute left-3 top-3 inline-flex min-h-8 items-center gap-1.5 rounded-full border border-white/15 bg-slate-900/45 px-2.5 text-[11px] font-semibold text-white/95 shadow-sm backdrop-blur-md">
        <Camera size={14} className="shrink-0 text-white/80" aria-hidden="true" />{property._mediaCount} media
      </span>}
      <button type="button" onClick={() => onToggleFavorite(property)} disabled={!favoriteStateReady || favoritePending}
        aria-label={!favoriteStateReady ? `Saved state loading for ${property.title}` : favoritePending ? `${isFavorite ? 'Removing' : 'Saving'} ${property.title}` : isFavorite ? `Remove ${property.title} from saved properties` : `Save ${property.title}`}
        aria-pressed={favoriteStateReady ? isFavorite : undefined} title={isFavorite ? 'Remove from saved properties' : 'Save property'}
        className={`absolute right-3 top-3 flex h-11 w-11 items-center justify-center rounded-full border border-white/20 bg-slate-900/45 text-white shadow-sm backdrop-blur-md transition-[transform,box-shadow,background-color] duration-300 group-hover/available-card:-translate-y-0.5 group-hover/available-card:shadow-md hover:scale-[1.04] hover:bg-slate-900/65 disabled:cursor-wait disabled:opacity-75 motion-reduce:transform-none motion-reduce:transition-none ${focusClass}`}>
        {favoritePending ? <LoaderCircle size={16} className="motion-safe:animate-spin" aria-hidden="true" /> : <Heart size={17} fill={isFavorite ? 'currentColor' : 'none'} className={isFavorite ? 'text-emerald-300' : 'text-white'} aria-hidden="true" />}
      </button>
    </div>
    <div className="relative z-10 mx-3 -mt-7 flex min-w-0 flex-1 flex-col rounded-[22px] border border-slate-200/90 bg-white p-3.5 shadow-[0_12px_28px_-12px_rgba(15,23,42,.12),0_4px_12px_-4px_rgba(15,23,42,.06)] transition-[transform,box-shadow,border-color] duration-300 ease-[cubic-bezier(.16,1,.3,1)] group-hover/available-card:-translate-y-[3px] group-hover/available-card:border-slate-300 group-hover/available-card:shadow-[0_20px_35px_-12px_rgba(15,23,42,.18),0_6px_14px_-4px_rgba(15,23,42,.08)] motion-reduce:transform-none motion-reduce:transition-none sm:mx-4 sm:-mt-9 sm:px-4 sm:pt-3.5 sm:pb-4">
      <div className="flex min-h-5 min-w-0 items-center justify-between gap-3 text-xs font-semibold text-slate-600">
        {location ? <span className="flex min-w-0 items-center gap-1.5" title={location}>
          <MapPin className="h-4 w-4 shrink-0 text-emerald-700" aria-hidden="true" />
          <span className="truncate">{location}</span>
        </span> : <span aria-hidden="true" />}
        {area && <span className="shrink-0 tabular-nums text-slate-500">{area}</span>}
      </div>

      <h3 className="mt-1.5 line-clamp-2 min-h-[2.85rem] break-words font-['Outfit',sans-serif] text-lg font-bold leading-snug text-slate-950 sm:text-xl">
        <button type="button" onClick={() => onOpenQuickView(property)} className={`text-left hover:text-emerald-800 ${focusClass}`}>{property.title?.trim() || 'Property'}</button>
      </h3>

      <div className="mt-1.5 flex min-h-5 min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-600">
        {bhk && <span className="inline-flex items-center gap-1.5 font-bold text-slate-800">
          <BedDouble className="h-4 w-4 shrink-0 text-slate-500" aria-hidden="true" />{bhk}
        </span>}
        {propertyType && <span>{propertyType}</span>}
        {furnishing && <span>{furnishing}</span>}
      </div>

      <div className="mt-auto pt-3">
        <div className={`grid gap-2 border-t border-slate-200/90 pt-2.5 ${deposit ? 'grid-cols-[minmax(0,1fr)_auto] items-end gap-3' : ''}`}>
          <div className="min-w-0">
            {priceLabel && <p className="text-[10px] font-bold uppercase tracking-[0.1em] text-slate-500">{priceLabel}</p>}
            <p className="mt-0.5 break-words font-['Outfit',sans-serif] text-[clamp(1.3rem,2vw,1.65rem)] font-extrabold leading-tight tabular-nums text-slate-950">
              {amount !== null ? `₹${amount.toLocaleString('en-IN')}` : 'On request'}
              {isRent && amount !== null && <span className="ml-1 text-xs font-medium tracking-normal text-slate-500">/ month</span>}
            </p>
          </div>
          {deposit && <div className="min-w-0 text-right">
            <p className="text-[10px] font-bold uppercase tracking-[0.1em] text-slate-500">Deposit</p>
            <p className="mt-0.5 break-words text-sm font-semibold leading-snug tabular-nums text-slate-700">{deposit}</p>
          </div>}
        </div>

        <div className="mt-2 min-h-4">
          <LastUpdatedMeta updatedAt={property.updatedAt} className="text-[11px]" />
        </div>
        <p className="mt-2.5 min-h-4 text-[11px] leading-4 text-slate-500">Contact details protected</p>

        <div className="mt-1.5 flex min-w-0 items-center justify-between gap-2">
          <button type="button" onClick={() => onOpenQuickView(property)} className={`group/quick inline-flex min-h-11 items-center gap-1 rounded-lg px-2.5 text-xs font-bold text-emerald-800 transition-colors duration-200 hover:bg-emerald-50 hover:text-emerald-900 ${focusClass}`}>
            <span>Quick view</span><ArrowRight size={14} className="transition-transform duration-300 group-hover/quick:translate-x-0.5 motion-reduce:transition-none" aria-hidden="true" />
          </button>
          <button type="button" onClick={() => onRequestVisit(property)} className={`min-h-11 shrink-0 rounded-full bg-emerald-800 px-3.5 text-xs font-semibold text-white shadow-[0_4px_12px_-8px_rgba(5,92,66,.65)] transition-[background-color,box-shadow,transform] duration-200 group-hover/available-card:shadow-[0_7px_16px_-8px_rgba(5,92,66,.7)] hover:bg-emerald-900 hover:shadow-[0_7px_16px_-8px_rgba(5,92,66,.68)] motion-safe:active:scale-[0.99] motion-reduce:transition-none ${focusClass}`}>Request visit</button>
        </div>
      </div>
    </div>
  </motion.article>;
};

const SavedHomeCard: React.FC<{
  property: Property;
  isFavorite: boolean;
  favoriteStateReady: boolean;
  favoritePending: boolean;
  onToggleFavorite: (property: Property) => void;
  onOpenQuickView: (property: Property) => void;
  onRequestVisit: (property: Property) => void;
}> = ({ property, isFavorite, favoriteStateReady, favoritePending, onToggleFavorite, onOpenQuickView, onRequestVisit }) => (
  <article className="group flex h-full min-h-[9.5rem] min-w-0 overflow-hidden rounded-[18px] border border-[#e6e9e2] bg-white shadow-[0_6px_24px_-21px_rgba(15,45,34,.36)] transition-[transform,box-shadow] duration-300 motion-safe:hover:-translate-y-0.5 hover:shadow-[0_15px_30px_-22px_rgba(15,45,34,.38)] motion-reduce:transform-none motion-reduce:transition-none">
    <div className="relative min-h-[9.5rem] w-[42%] max-w-[11rem] shrink-0 overflow-hidden bg-[#e8e6df]">
      <button type="button" onClick={() => onOpenQuickView(property)} aria-label={`Quick view: ${property.title || 'property'}`} className={`block h-full w-full ${focusClass}`}>
        <PropertyImage src={property.images?.[0]} alt={property.title ? `${property.title} photo` : 'Property photo'} editorialHover />
      </button>
      {typeof property._mediaCount === 'number' && property._mediaCount > 0 && <span className="absolute bottom-2 left-2 rounded-full bg-slate-950/70 px-2 py-1 text-[9px] font-medium text-white">{property._mediaCount} media</span>}
      <button type="button" onClick={() => onToggleFavorite(property)} disabled={!favoriteStateReady || favoritePending}
        aria-label={favoritePending ? `Updating saved state for ${property.title || 'property'}` : isFavorite ? `Remove ${property.title || 'property'} from saved homes` : `Save ${property.title || 'property'}`}
        aria-pressed={favoriteStateReady ? isFavorite : undefined} title={isFavorite ? 'Remove from saved homes' : 'Save property'}
        className={`absolute right-2 top-2 flex h-11 w-11 items-center justify-center rounded-full border border-white/90 bg-white/90 text-emerald-900 shadow-sm backdrop-blur-[2px] hover:bg-white disabled:cursor-wait disabled:opacity-75 ${focusClass}`}>
        {favoritePending ? <LoaderCircle size={16} className="motion-safe:animate-spin" aria-hidden="true" /> : <Heart size={17} fill={isFavorite ? 'currentColor' : 'none'} className={isFavorite ? 'text-emerald-800' : 'text-slate-700'} aria-hidden="true" />}
      </button>
    </div>
    <div className="flex min-w-0 flex-1 flex-col p-3">
      <PropertyFacts property={property} />
      <h3 className="mt-1 line-clamp-2 min-h-[2.2rem] break-words font-serif text-[15px] font-medium leading-[1.2] tracking-tight text-slate-950">
        <button type="button" onClick={() => onOpenQuickView(property)} className={`text-left hover:text-emerald-800 ${focusClass}`}>{property.title?.trim() || 'Property'}</button>
      </h3>
      <p className="mt-1 truncate text-xs text-slate-600">{[property.sector, property.city].filter((part): part is string => Boolean(part?.trim())).join(', ')}</p>
      <PropertyPrice property={property} compact />
      <div className="mt-auto flex min-w-0 flex-wrap items-center justify-between gap-x-1 border-t border-slate-200/70 pt-1.5">
        <button type="button" onClick={() => onOpenQuickView(property)} className={`inline-flex min-h-11 shrink-0 items-center gap-1 rounded-lg px-1 text-[11px] font-semibold text-emerald-900 hover:bg-emerald-50 ${focusClass}`}>Quick view <ArrowRight size={12} aria-hidden="true" /></button>
        <button type="button" onClick={() => onRequestVisit(property)} aria-label={`Request visit for ${property.title || 'property'}`} className={`min-h-11 shrink-0 whitespace-nowrap rounded-full bg-emerald-800 px-2.5 text-[10px] font-semibold text-white hover:bg-emerald-900 ${focusClass}`}>Request visit</button>
      </div>
    </div>
  </article>
);

const SavedStackCard: React.FC<{
  properties: Property[];
  additionalCount: number;
  hasMorePages: boolean;
  expanded: boolean;
  onExpand: () => void;
}> = ({ properties, additionalCount, hasMorePages, expanded, onExpand }) => {
  const reduceMotion = useReducedMotion();
  const previews = properties.slice(0, 3);
  const label = additionalCount > 0
    ? `Show ${additionalCount} more saved ${additionalCount === 1 ? 'home' : 'homes'}`
    : 'Show more saved homes';
  return <motion.button
    type="button"
    aria-expanded={expanded}
    aria-controls="tenant-saved-home-grid"
    aria-label={additionalCount > 0 ? `Reveal ${additionalCount} additional loaded saved homes` : 'Reveal more saved homes'}
    onClick={onExpand}
    whileHover={reduceMotion ? undefined : { y: -2 }}
    whileTap={reduceMotion ? undefined : { scale: 0.99 }}
    className={`group relative flex h-full min-h-[9.5rem] min-w-0 items-center gap-2 overflow-hidden rounded-[18px] border border-[#dfe8de] bg-[linear-gradient(140deg,#f3f7ef,#ffffff_55%,#e7f0e7)] p-2.5 text-left shadow-[0_7px_26px_-20px_rgba(15,45,34,.38)] transition-[box-shadow,transform] duration-300 hover:shadow-[0_16px_32px_-22px_rgba(15,45,34,.42)] motion-reduce:transition-none ${focusClass}`}
  >
    <span className="relative block h-[5.5rem] w-[46%] shrink-0" aria-hidden="true">
      {previews.map((property, index) => {
        const firstImage = property.images?.[0];
        const source = typeof firstImage === 'string' ? firstImage.trim() : '';
        const fan = !reduceMotion && previews.length > 1
            ? index === 0
              ? 'group-hover:[--stack-x:-8px] group-hover:[--stack-rotate:-1.5deg]'
              : index === previews.length - 1
              ? 'group-hover:[--stack-x:6px] group-hover:[--stack-y:-4px] group-hover:[--stack-rotate:1.5deg]'
              : 'group-hover:[--stack-x:3px] group-hover:[--stack-y:-5px] group-hover:[--stack-rotate:0deg]'
          : '';
        return <span key={property.id}
          className={`absolute left-1/2 top-1/2 h-[4.25rem] w-[92%] overflow-hidden rounded-lg border-[3px] border-white bg-[#e5e9e2] shadow-[0_8px_18px_-10px_rgba(15,45,34,.48)] [transform:translate3d(calc(-50%_+_var(--stack-x)),calc(-50%_+_var(--stack-y)),0)_rotate(var(--stack-rotate))] transition-transform duration-300 motion-reduce:transition-none ${fan}`}
          style={{ '--stack-x': `${(index - (previews.length - 1) / 2) * 6}px`, '--stack-y': `${Math.abs(index - 1) * 2}px`, '--stack-rotate': `${(index - (previews.length - 1) / 2) * 1.2}deg`, zIndex: index + 1 } as React.CSSProperties}>
          {source ? <img src={buildCloudinaryUrl(source, 'DISCOVERY_CARD')} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" /> : <span className="flex h-full items-center justify-center text-emerald-800"><HomeIcon size={24} /></span>}
        </span>;
      })}
      {additionalCount > 0 && <span className="absolute right-0 top-0 rounded-full bg-emerald-900 px-2 py-1 text-[9px] font-semibold text-white shadow-sm transition-transform duration-300 group-hover:-translate-y-0.5 motion-reduce:transition-none">+{additionalCount}{hasMorePages ? ' loaded' : ''}</span>}
      {hasMorePages && additionalCount === 0 && <span className="absolute right-0 top-0 rounded-full bg-emerald-900 px-2 py-1 text-[9px] font-semibold text-white shadow-sm">More</span>}
    </span>
    <span className="block min-w-0 flex-1">
      <span className="block truncate text-sm font-semibold text-slate-950">{additionalCount > 0 ? hasMorePages ? `${additionalCount} more loaded` : `${additionalCount} more saved` : 'More saved homes'}</span>
      <span className="mt-1 block text-[11px] leading-4 text-slate-600">{hasMorePages ? 'Load another page when you’re ready.' : 'Reveal your saved collection.'}</span>
      <span className="mt-1 inline-flex min-h-11 max-w-full items-center gap-1 text-xs font-semibold text-emerald-800"><span className="truncate">{label}</span><ArrowRight size={14} aria-hidden="true" className="shrink-0 transition-transform duration-200 group-hover:translate-x-0.5 motion-reduce:transform-none" /></span>
    </span>
  </motion.button>;
};

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
  user, properties, discoveryState, discoveryLoadedKey, discoveryCity, searchFilters, hasMoreProperties,
  loadingMoreProperties, loadMorePropertiesError, onSearchHomes, onRetryDiscovery,
  onLoadMoreProperties, onRequestVisit, onViewProperty
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
  const [savedHomesExpanded, setSavedHomesExpanded] = useState(false);
  const [savedHomesVisibleLimit, setSavedHomesVisibleLimit] = useState(() =>
    typeof window === 'undefined' ? 2 : savedHomesVisibleLimitForWidth(window.innerWidth));
  const [stickySearchMode, setStickySearchMode] = useState<'hero' | 'sticky' | 'beacon'>('hero');
  const [heroSearchAnchorVisible, setHeroSearchAnchorVisible] = useState(true);
  const [searchEngaged, setSearchEngaged] = useState(false);
  const [searchActivityVersion, setSearchActivityVersion] = useState(0);
  const [discoverySearchCollision, setDiscoverySearchCollision] = useState(false);
  const [manualSearchOpenGrace, setManualSearchOpenGrace] = useState(false);
  const [searchBeaconTop, setSearchBeaconTop] = useState<number | null>(null);
  const [searchMorphActive, setSearchMorphActive] = useState(false);
  const [beaconAcknowledgement, setBeaconAcknowledgement] = useState(0);
  const [tenantHeroVisible, setTenantHeroVisible] = useState(true);
  const [heroSearchRevealVersion, setHeroSearchRevealVersion] = useState(0);
  const [mobileDockIsScrolling, setMobileDockIsScrolling] = useState(false);
  const [mobileDockActiveItem, setMobileDockActiveItem] = useState<TenantMobileDockItem>('home');
  const [quickRefineSheetOpen, setQuickRefineSheetOpen] = useState(false);
  const mobileDockSelectionPinnedRef = useRef(false);
  const heroSearchWasAwayRef = useRef(false);
  const mobileDockScrollIdleTimerRef = useRef<number | null>(null);
  const searchMorphOverlayRef = useRef<TenantSearchMorphOverlay | null>(null);
  const searchMorphFrameRef = useRef<number | null>(null);
  const searchMorphCleanupTimerRef = useRef<number | null>(null);
  const pendingHeroFilterScrollRef = useRef<PendingTenantHeroFilterScroll | null>(null);
  const pendingHeroFilterScrollFrameRef = useRef<number | null>(null);
  const visitHistoryDetailsRef = useRef<HTMLDetailsElement>(null);
  const quickRefineOpenRef = useRef<(() => void) | null>(null);
  const savedHomesRequestRef = useRef<AbortController | null>(null);
  const savedHomesLoadingMoreRef = useRef(false);
  const favoriteMutationRef = useRef<Set<string>>(new Set());
  const favoriteMutationRevisionRef = useRef<Map<string, number>>(new Map());
  const favoriteSession = readTenantVisitSession(user.id);
  const currentDiscoveryKey = discoverySearchKey(searchFilters);
  const mobileDockBadges = tenantMobileDockBadges(searchFilters);
  const showTenantMobileDock = shouldRenderTenantMobileDock(
    tenantHeroVisible, quickRefineSheetOpen, previewPropertyId !== null
  );
  const tenantDiscoveryRailTop = 'calc(76px + env(safe-area-inset-top) + 18px)';
  const quickRefineRentBounds = useMemo(() => getQuickRefineRentBounds(searchFilters),
    [searchFilters.minRent, searchFilters.maxRent]);
  const cancelSearchMorph = useCallback(() => {
    if (searchMorphFrameRef.current !== null) {
      window.cancelAnimationFrame(searchMorphFrameRef.current);
      searchMorphFrameRef.current = null;
    }
    if (searchMorphCleanupTimerRef.current !== null) {
      window.clearTimeout(searchMorphCleanupTimerRef.current);
      searchMorphCleanupTimerRef.current = null;
    }
    searchMorphOverlayRef.current?.cancel();
    searchMorphOverlayRef.current = null;
    setSearchMorphActive(false);
  }, []);
  const startSearchMorph = useCallback((targetMode: 'sticky' | 'beacon') => {
    const sourceMode = stickySearchMode;
    if (sourceMode !== 'sticky' && sourceMode !== 'beacon') {
      setStickySearchMode(targetMode);
      return;
    }
    if (sourceMode === targetMode) return;
    const plan = tenantSearchMorphPlan(sourceMode, targetMode, reduceMotion, window.innerWidth);
    cancelSearchMorph();
    if (targetMode === 'beacon') setSearchBeaconTop(Math.round(window.innerHeight * 0.44 - 24));
    const source = document.getElementById('compact-discovery-context');
    const overlay = plan.travels && source ? createTenantSearchMorphOverlay(source, sourceMode) : null;
    if (!overlay) {
      setStickySearchMode(targetMode);
      return;
    }

    searchMorphOverlayRef.current = overlay;
    setSearchMorphActive(true);
    setStickySearchMode(targetMode);
    let attempts = 0;
    const beginAnimation = () => {
      searchMorphFrameRef.current = null;
      if (searchMorphOverlayRef.current !== overlay) return;
      const destination = document.getElementById('compact-discovery-context');
      const icon = destination?.querySelector(`[data-tenant-search-mode-icon="${targetMode}"]`);
      if (!destination || !icon) {
        if (attempts++ < 2) searchMorphFrameRef.current = window.requestAnimationFrame(beginAnimation);
        else cancelSearchMorph();
        return;
      }
      overlay.animateTo(destination, targetMode, () => {
        if (searchMorphOverlayRef.current !== overlay) return;
        setSearchMorphActive(false);
        if (targetMode === 'beacon' && !reduceMotion) setBeaconAcknowledgement(value => value + 1);
        searchMorphCleanupTimerRef.current = window.setTimeout(() => {
          if (searchMorphOverlayRef.current !== overlay) return;
          overlay.cancel();
          searchMorphOverlayRef.current = null;
          searchMorphCleanupTimerRef.current = null;
        }, 40);
      });
    };
    searchMorphFrameRef.current = window.requestAnimationFrame(beginAnimation);
  }, [cancelSearchMorph, reduceMotion, stickySearchMode]);

  const collapseSearchToBeacon = useCallback((reason: 'inactivity' | 'discovery') => {
    if (!shouldCollapseTenantSearch(reason, {
      mode: stickySearchMode,
      heroVisible: heroSearchAnchorVisible,
      engaged: searchEngaged,
      discoveryCollision: discoverySearchCollision,
      manualOpenGrace: manualSearchOpenGrace
    })) return;
    setManualSearchOpenGrace(false);
    startSearchMorph('beacon');
  }, [discoverySearchCollision, heroSearchAnchorVisible, manualSearchOpenGrace, searchEngaged, startSearchMorph, stickySearchMode]);

  useEffect(() => {
    if (!searchMorphActive) return undefined;
    const cancelOnResize = () => cancelSearchMorph();
    window.addEventListener('resize', cancelOnResize, { passive: true });
    return () => window.removeEventListener('resize', cancelOnResize);
  }, [cancelSearchMorph, searchMorphActive]);

  useEffect(() => () => {
    if (searchMorphFrameRef.current !== null) window.cancelAnimationFrame(searchMorphFrameRef.current);
    if (searchMorphCleanupTimerRef.current !== null) window.clearTimeout(searchMorphCleanupTimerRef.current);
    searchMorphOverlayRef.current?.cancel();
    searchMorphOverlayRef.current = null;
  }, []);

  const reportSearchEngagement = useCallback((engaged: boolean) => setSearchEngaged(engaged), []);
  const recordSearchInteraction = useCallback(() => setSearchActivityVersion(version => version + 1), []);
  const expandSearchBeacon = useCallback(() => {
    setManualSearchOpenGrace(true);
    startSearchMorph('sticky');
  }, [startSearchMorph]);
  const reportQuickRefineSheetOpen = useCallback((isOpen: boolean) => {
    setQuickRefineSheetOpen(isOpen);
    if (isOpen) {
      mobileDockSelectionPinnedRef.current = true;
      setMobileDockActiveItem('filters');
      return;
    }
    mobileDockSelectionPinnedRef.current = false;
    const candidates: Array<{ id: string; item: TenantMobileDockItem }> = [
      { id: 'tenant-home-search', item: 'home' },
      { id: 'saved-homes-title', item: 'saved' },
      { id: 'visit-history-title', item: 'visits' },
      { id: 'discover-homes-title', item: 'home' }
    ];
    const visible = candidates.map(candidate => ({
      ...candidate,
      rect: document.getElementById(candidate.id)?.getBoundingClientRect()
    })).filter(candidate => candidate.rect && candidate.rect.bottom > 0 && candidate.rect.top < window.innerHeight)
      .sort((left, right) => Math.abs((left.rect?.top ?? 0) - window.innerHeight * 0.4)
        - Math.abs((right.rect?.top ?? 0) - window.innerHeight * 0.4))[0];
    if (visible) setMobileDockActiveItem(visible.item);
  }, []);

  useEffect(() => {
    const hero = document.getElementById('tenant-home-search');
    if (!hero) {
      setTenantHeroVisible(false);
      return undefined;
    }
    const observer = new IntersectionObserver(entries => {
      const entry = entries.find(candidate => candidate.target === hero);
      if (entry) setTenantHeroVisible(entry.intersectionRatio >= 0.12);
    }, { threshold: [0, 0.12] });
    observer.observe(hero);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const handleScroll = () => {
      setMobileDockIsScrolling(current => current ? current : true);
      if (mobileDockScrollIdleTimerRef.current !== null) {
        window.clearTimeout(mobileDockScrollIdleTimerRef.current);
      }
      mobileDockScrollIdleTimerRef.current = window.setTimeout(() => {
        mobileDockScrollIdleTimerRef.current = null;
        setMobileDockIsScrolling(false);
      }, 200);
    };
    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => {
      window.removeEventListener('scroll', handleScroll);
      if (mobileDockScrollIdleTimerRef.current !== null) {
        window.clearTimeout(mobileDockScrollIdleTimerRef.current);
        mobileDockScrollIdleTimerRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return undefined;
    const targets: Array<{ id: string; item: TenantMobileDockItem }> = [
      { id: 'tenant-home-search', item: 'home' },
      { id: 'saved-homes-title', item: 'saved' },
      { id: 'visit-history-title', item: 'visits' },
      { id: 'discover-homes-title', item: 'home' }
    ];
    const observer = new IntersectionObserver(entries => {
      if (mobileDockSelectionPinnedRef.current) return;
      const closest = entries.filter(entry => entry.isIntersecting)
        .sort((left, right) => Math.abs(left.boundingClientRect.top - window.innerHeight * 0.38)
          - Math.abs(right.boundingClientRect.top - window.innerHeight * 0.38))[0];
      const matched = closest && targets.find(target => document.getElementById(target.id) === closest.target);
      if (matched) setMobileDockActiveItem(matched.item);
    }, { rootMargin: '-120px 0px -280px 0px', threshold: 0 });
    targets.forEach(({ id }) => {
      const target = document.getElementById(id);
      if (target) observer.observe(target);
    });
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const clearPinnedSelectionForUserScroll = (event: KeyboardEvent) => {
      const target = event.target;
      if (target instanceof HTMLElement && (target.isContentEditable || target.matches('input, textarea, select'))) return;
      if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) {
        mobileDockSelectionPinnedRef.current = false;
      }
    };
    const clearPinnedSelection = () => { mobileDockSelectionPinnedRef.current = false; };
    window.addEventListener('wheel', clearPinnedSelection, { passive: true });
    window.addEventListener('touchstart', clearPinnedSelection, { passive: true });
    window.addEventListener('keydown', clearPinnedSelectionForUserScroll);
    return () => {
      window.removeEventListener('wheel', clearPinnedSelection);
      window.removeEventListener('touchstart', clearPinnedSelection);
      window.removeEventListener('keydown', clearPinnedSelectionForUserScroll);
    };
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
    const updateLimit = () => setSavedHomesVisibleLimit(savedHomesVisibleLimitForWidth(window.innerWidth));
    window.addEventListener('resize', updateLimit, { passive: true });
    return () => window.removeEventListener('resize', updateLimit);
  }, []);

  useEffect(() => {
    const heroSearchAnchor = document.getElementById('tenant-search-placeholder');
    if (!heroSearchAnchor) return undefined;

    const navbar = document.querySelector<HTMLElement>('[data-pathome-header="global"]');
    const navbarHeight = navbar?.getBoundingClientRect().height ?? 80;
    const setHeroVisibility = (visible: boolean) => {
      if (visible && heroSearchWasAwayRef.current) {
        heroSearchWasAwayRef.current = false;
        if (window.innerWidth < 768) setHeroSearchRevealVersion(version => version + 1);
      } else if (!visible) {
        heroSearchWasAwayRef.current = true;
      }
      if (visible) {
        cancelSearchMorph();
        setDiscoverySearchCollision(false);
        setManualSearchOpenGrace(false);
      }
      setHeroSearchAnchorVisible(visible);
      setStickySearchMode(current => visible ? 'hero' : current === 'hero' ? 'sticky' : current);
    };
    const syncVisibility = () => {
      const bounds = heroSearchAnchor.getBoundingClientRect();
      const navbarBottom = navbar?.getBoundingClientRect().bottom ?? navbarHeight;
      setHeroVisibility(bounds.bottom > navbarBottom + 8 && bounds.top < window.innerHeight);
    };
    let observer: IntersectionObserver | null = null;
    if (typeof IntersectionObserver !== 'undefined') {
      observer = new IntersectionObserver(entries => {
        const entry = entries[entries.length - 1];
        if (entry) setHeroVisibility(entry.isIntersecting);
      }, { rootMargin: `-${Math.ceil(navbarHeight + 8)}px 0px 0px 0px`, threshold: 0 });
      observer.observe(heroSearchAnchor);
    } else {
      window.addEventListener('scroll', syncVisibility, { passive: true });
    }
    window.addEventListener('resize', syncVisibility, { passive: true });
    syncVisibility();
    return () => {
      observer?.disconnect();
      window.removeEventListener('scroll', syncVisibility);
      window.removeEventListener('resize', syncVisibility);
    };
  }, [cancelSearchMorph]);

  useEffect(() => {
    const sentinel = document.getElementById('tenant-discovery-collision-sentinel');
    const search = document.getElementById('compact-discovery-context');
    if (!sentinel || !search || stickySearchMode !== 'sticky' || discoverySearchCollision
      || heroSearchAnchorVisible || searchEngaged || searchMorphActive) return undefined;

    let observer: IntersectionObserver | null = null;
    let collisionFrame: number | null = null;
    const isEligible = () => window.innerWidth >= 1024
      && !heroSearchAnchorVisible && !searchEngaged && !searchMorphActive;
    const collisionReached = () => {
      if (!isEligible()) return false;
      const band = tenantSearchCollisionBand(search.getBoundingClientRect().bottom, window.innerHeight);
      return tenantSearchCollisionReached(sentinel.getBoundingClientRect().top, band);
    };
    const checkCollision = () => {
      collisionFrame = null;
      if (!collisionReached()) return;
      observer?.disconnect();
      observer = null;
      setDiscoverySearchCollision(true);
    };
    const scheduleCollisionCheck = () => {
      if (!isEligible() || collisionFrame !== null) return;
      collisionFrame = window.requestAnimationFrame(checkCollision);
    };
    const observeCollisionLine = () => {
      observer?.disconnect();
      observer = null;
      if (window.innerWidth < 1024) {
        setDiscoverySearchCollision(false);
        return;
      }
      if (!isEligible()) return;
      const band = tenantSearchCollisionBand(search.getBoundingClientRect().bottom, window.innerHeight);
      if (tenantSearchCollisionReached(sentinel.getBoundingClientRect().top, band)) {
        setDiscoverySearchCollision(true);
        return;
      }
      if (typeof IntersectionObserver === 'undefined') return;
      const bottomInset = Math.max(0, window.innerHeight - band.bottom);
      observer = new IntersectionObserver(scheduleCollisionCheck,
        { rootMargin: `-${band.top}px 0px -${bottomInset}px 0px`, threshold: 0 });
      observer.observe(sentinel);
    };

    observeCollisionLine();
    const onResize = () => {
      observeCollisionLine();
      scheduleCollisionCheck();
    };
    window.addEventListener('resize', onResize, { passive: true });
    // IO catches ordinary crossings; this rAF-coalesced geometry check also catches a fast
    // scroll that moves the sentinel completely past the narrow observation band.
    window.addEventListener('scroll', scheduleCollisionCheck, { passive: true });
    return () => {
      observer?.disconnect();
      if (collisionFrame !== null) window.cancelAnimationFrame(collisionFrame);
      window.removeEventListener('resize', onResize);
      window.removeEventListener('scroll', scheduleCollisionCheck);
    };
  }, [discoverySearchCollision, heroSearchAnchorVisible, searchEngaged, searchMorphActive, stickySearchMode]);

  useEffect(() => {
    if (!discoverySearchCollision) return;
    collapseSearchToBeacon('discovery');
  }, [collapseSearchToBeacon, discoverySearchCollision]);

  useEffect(() => {
    if (stickySearchMode !== 'sticky' || heroSearchAnchorVisible || searchEngaged) return undefined;
    const timeout = window.setTimeout(() => {
      collapseSearchToBeacon('inactivity');
    }, 5000);
    return () => window.clearTimeout(timeout);
  }, [stickySearchMode, heroSearchAnchorVisible, searchEngaged, searchActivityVersion, collapseSearchToBeacon]);

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
    if (stickySearchMode !== 'beacon') {
      setSearchBeaconTop(null);
      return undefined;
    }
    const placeBeacon = () => {
      setSearchBeaconTop(Math.round(window.innerHeight * 0.44 - 24));
    };
    let frame = window.requestAnimationFrame(placeBeacon);
    const schedulePlacement = () => {
      window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(placeBeacon);
    };
    window.addEventListener('resize', schedulePlacement, { passive: true });
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener('resize', schedulePlacement);
    };
  }, [stickySearchMode]);

  useEffect(() => { setSavedHomesExpanded(false); }, [favoriteSession?.key]);

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
    setTenantSessionAction(sessionId);
    setTenantSessionError(null);
    try {
      const code = await visitExecutionService.issueStartCode(sessionId);
      if (!isCurrentTenantVisitSession(requestSession)) return;
      setTenantSessionCodes(current => ({ ...current, [sessionId]: code }));
    } catch (error) {
      setTenantSessionError(error instanceof Error ? error.message : 'Could not prepare a visit start code. Please retry.');
    } finally { setTenantSessionAction(null); }
  };
  const confirmVisitChange = async (sessionId: number, action: 'ACCEPT_RESCHEDULE' | 'REJECT_RESCHEDULE' | 'DISPUTE_NO_SHOW', expectedSessionVersion: number) => {
    const requestSession = readTenantVisitSession(user.id);
    if (!requestSession) { setTenantSessionError('Please sign in again to manage this visit.'); return; }
    setTenantSessionAction(sessionId);
    setTenantSessionError(null);
    try {
      await visitExecutionService.confirmTenant(sessionId, action, createVisitOperationId(), expectedSessionVersion);
      if (!isCurrentTenantVisitSession(requestSession)) return;
      setTenantSessionCodes(current => { const next = { ...current }; delete next[sessionId]; return next; });
      setTenantSessionsReload(value => value + 1);
    } catch (error) {
      setTenantSessionError(error instanceof Error ? error.message : 'Could not record your visit response. Please retry.');
    } finally { setTenantSessionAction(null); }
  };
  const visibleHistory: HistoryState = !session ? { ...emptyHistory, status: 'error' } :
    session.key === history.identityKey ? history : emptyHistory;
  const view = tenantVisitView(visibleHistory.status, visibleHistory.requests);
  const firstName = user.fullName?.trim().split(/\s+/)[0];
  const savedHomesIdentityMatches = Boolean(favoriteSession && savedHomesState.identityKey === favoriteSession.key);
  const visibleSavedHomes = savedHomesIdentityMatches && savedHomesState.status !== 'error'
    ? savedHomesState.properties : [];
  const presentedSavedHomes = savedHomesForPresentation(visibleSavedHomes, savedHomesVisibleLimit, savedHomesExpanded);
  const additionalSavedHomes = visibleSavedHomes.slice(savedHomesVisibleLimit);
  const hasSavedHomesStack = additionalSavedHomes.length > 0 || Boolean(savedHomesIdentityMatches && savedHomesState.status === 'ready' && savedHomesState.hasMore);
  const visibleFavoriteIds = new Set<number>([
    ...(favoriteSession && favoriteState.identityKey === favoriteSession.key && favoriteState.status === 'ready'
      ? favoriteState.favoriteIds : []),
    ...visibleSavedHomes.map(property => property.id)
  ]);
  const availableProperties = removeSavedHomesFromDiscovery(properties, visibleFavoriteIds);
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
    mobileDockSelectionPinnedRef.current = true;
    setMobileDockActiveItem(item);
    const targetId = tenantMobileDockTarget(item);
    if (!targetId) return;
    if (item === 'visits' && visitHistoryDetailsRef.current) visitHistoryDetailsRef.current.open = true;
    const target = document.getElementById(targetId);
    if (!target) return;
    target.scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
      block: 'start'
    });
    const focusTarget = target.matches('[data-tenant-nav-focus]')
      ? target
      : target.querySelector<HTMLElement>('[data-tenant-nav-focus]');
    focusTarget?.focus({ preventScroll: true });
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
        ? 'border-emerald-200/70 bg-emerald-300/30 text-white shadow-[0_0_16px_rgba(52,211,153,.16)]'
        : 'border-white/30 bg-slate-950/40 text-white/95 shadow-[0_1px_4px_rgba(0,0,0,.12)] backdrop-blur-sm hover:border-white/50 hover:bg-slate-900/50';
      const desktopClass = selected
        ? 'border-emerald-200/80 bg-emerald-300/25 text-white shadow-[0_0_20px_rgba(52,211,153,.16)]'
        : 'border-white/25 bg-slate-950/25 text-white/90 backdrop-blur-sm hover:border-white/45 hover:bg-white/10';
      return <button key={value} type="button" aria-pressed={selected} onClick={() => toggleHeroQuickFilter('bhk', value)}
        className={`inline-flex min-h-11 shrink-0 items-center gap-2 rounded-full border px-4 text-sm font-semibold transition-[background-color,border-color,color,transform] duration-200 focus-visible:outline-none focus-visible:ring-2 ${mobile ? 'focus-visible:ring-emerald-200' : 'focus-visible:ring-emerald-200'} motion-reduce:transition-none ${mobile ? mobileClass : desktopClass}`}>
        <BedDouble size={17} aria-hidden="true" />{value}
      </button>;
    })}
    {HERO_PROPERTY_FILTERS.map(({ label, value }) => {
      const selected = searchFilters.propertyType === value;
      const mobileClass = selected
        ? 'border-emerald-200/70 bg-emerald-300/30 text-white shadow-[0_0_16px_rgba(52,211,153,.16)]'
        : 'border-white/30 bg-slate-950/40 text-white/95 shadow-[0_1px_4px_rgba(0,0,0,.12)] backdrop-blur-sm hover:border-white/50 hover:bg-slate-900/50';
      const desktopClass = selected
        ? 'border-emerald-200/80 bg-emerald-300/25 text-white shadow-[0_0_20px_rgba(52,211,153,.16)]'
        : 'border-white/25 bg-slate-950/25 text-white/90 backdrop-blur-sm hover:border-white/45 hover:bg-white/10';
      return <button key={value} type="button" aria-pressed={selected} onClick={() => toggleHeroQuickFilter('propertyType', value)}
        className={`inline-flex min-h-11 shrink-0 items-center gap-2 rounded-full border px-4 text-sm font-semibold transition-[background-color,border-color,color,transform] duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-200 motion-reduce:transition-none ${mobile ? mobileClass : desktopClass}`}>
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
    <>
    <main aria-hidden={previewPropertyId !== null} className="relative -mt-[calc(72px+env(safe-area-inset-top))] min-w-0 bg-[#f7f7f2] pb-[calc(7.25rem+env(safe-area-inset-bottom))] text-slate-950 lg:pb-16">
      <section id="tenant-home-search" tabIndex={-1} aria-label="Tenant home search" className={`relative z-20 isolate flex min-h-[45rem] min-w-0 scroll-mt-[calc(72px+env(safe-area-inset-top))] flex-col overflow-visible bg-[#082a22] text-white outline-none lg:min-h-[40rem] ${stickySearchMode !== 'hero' ? 'z-[90]' : ''}`}>
        <img src="/assets/pathome_tenant_hero_dream_home.webp" alt="" aria-hidden="true" fetchPriority="high" decoding="async" className="pointer-events-none absolute inset-0 -z-20 h-full w-full object-cover object-[88%_center] lg:object-center" />
        <div className="pointer-events-none absolute inset-0 -z-10 bg-[linear-gradient(180deg,rgba(3,20,16,.56)_0%,rgba(3,24,19,.36)_32%,rgba(3,24,19,.77)_100%),linear-gradient(90deg,rgba(2,26,20,.78)_0%,rgba(4,31,24,.38)_55%,rgba(4,31,24,.04)_100%)] lg:bg-[linear-gradient(90deg,rgba(2,25,19,.96)_0%,rgba(3,32,24,.91)_27%,rgba(3,34,25,.68)_44%,rgba(4,31,23,.12)_70%,rgba(4,28,20,.02)_100%)]" aria-hidden="true" />

        <div className="relative mx-auto flex w-full max-w-[1600px] flex-1 flex-col px-4 pb-6 pt-[calc(100px+env(safe-area-inset-top))] sm:px-7 sm:pb-7 sm:pt-[calc(108px+env(safe-area-inset-top))] lg:px-8 lg:pb-8 lg:pt-[calc(116px+env(safe-area-inset-top))]">
          <div className="order-1 relative z-10 max-w-[56rem]">
            <p className="text-[10px] font-bold uppercase tracking-[0.28em] text-emerald-200 sm:text-xs">Your home search</p>
            <h1 data-tenant-nav-focus tabIndex={-1} className="mt-3 max-w-[55rem] break-words font-serif text-[clamp(2.35rem,9.5vw,4.75rem)] font-normal leading-[0.98] tracking-[-0.055em] text-[#fffaf0] focus:outline-none lg:text-[4.75rem]">
              {firstName ? <><span className="block">Find your place,</span><span className="block text-[#54e0bd]">{firstName}.</span></> : <span className="block">Find your place.</span>}
            </h1>
            <p className="mt-3 max-w-[32rem] text-[13px] leading-[1.55] text-white/90 sm:text-base">Explore homes that fit. See the details, then request a visit when one feels right.</p>
          </div>

          <div role="group" aria-label="Quick filters" className="order-3 mt-5 hidden max-w-[58rem] gap-2 lg:flex lg:flex-wrap lg:overflow-visible">
            {renderHeroQuickFilterControls()}
          </div>

          <div aria-label="Pathome product highlights" className="order-4 mt-6 hidden max-w-[58rem] grid-cols-3 gap-6 border-t border-white/20 pt-4 lg:grid">
            {[
              { icon: HomeIcon, title: 'Clear details', copy: 'Property information in one place' },
              { icon: MapPin, title: 'Location-led search', copy: 'Find homes by city and locality' },
              { icon: CalendarDays, title: 'Visit requests', copy: 'Request a visit when a home fits' }
            ].map(({ icon: Icon, title, copy }) => <div key={title} className="flex min-w-0 items-start gap-3 text-white/90">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-emerald-200/30 bg-slate-950/35 text-emerald-200"><Icon size={17} aria-hidden="true" /></span>
              <span className="min-w-0 pt-0.5"><span className="block text-xs font-semibold leading-4">{title}</span><span className="mt-0.5 block max-w-[12rem] text-[11px] leading-4 text-white/70">{copy}</span></span>
            </div>)}
          </div>

          <div id="tenant-search-placeholder" className="relative z-40 order-2 mt-auto w-full min-w-0 pt-7 lg:mt-6 lg:w-[min(52vw,58rem)] lg:max-w-full lg:pt-0">
            <CompactSearchContext appearance="tenant-hero" alwaysEditing stickyMode={stickySearchMode} beaconTop={searchBeaconTop}
              searchMorphActive={searchMorphActive} beaconAcknowledgement={beaconAcknowledgement}
              heroRevealVersion={heroSearchRevealVersion}
              searchPlaceholder="Search locality, landmark, or property type" city={discoveryCity} filters={searchFilters}
              onInteraction={recordSearchInteraction} onEngagementChange={reportSearchEngagement} onBeaconExpand={expandSearchBeacon}
              onSearch={handleSmartSearch}
              onManualCityChange={city => applySmartSearch(resetFiltersForManualCityChange(city))}
              onClearAll={clearTenantSearch} />
          </div>

          <div role="group" aria-label="Quick filters" className="order-3 mt-3 flex w-full min-w-0 gap-2 overflow-x-auto overscroll-x-contain pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden lg:hidden">
            {renderHeroQuickFilterControls(true)}
          </div>

          <div aria-label="Pathome promise" className="order-4 mt-3 grid w-full max-w-[58rem] grid-cols-3 items-center gap-1 border-t border-white/20 pt-3 lg:hidden">
            {[
              { icon: HomeIcon, title: 'Clear details' },
              { icon: MapPin, title: 'Location-led search' },
              { icon: CalendarDays, title: 'Visit requests' }
            ].map(({ icon: Icon, title }) => <div key={title} className="flex min-w-0 flex-col items-center gap-1 text-center text-white/90">
              <Icon size={16} strokeWidth={1.8} className="shrink-0 text-emerald-200" aria-hidden="true" />
              <span className="max-w-full text-[9px] font-medium leading-[1.15] tracking-[-0.01em] sm:text-[10px]">{title}</span>
            </div>)}
          </div>
        </div>

      </section>

      <div className="relative z-0 mx-auto w-full max-w-[1440px] min-w-0 px-4 pt-6 sm:px-6 sm:pt-7 lg:px-8 lg:pt-8">

        <section aria-labelledby="saved-homes-title" className="mb-7 mt-5 min-w-0 sm:mb-8 sm:mt-6">
          <div className="mb-3 flex flex-wrap items-end justify-between gap-x-4 gap-y-1">
            <div><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-emerald-800">Keep close</p>
              <h2 id="saved-homes-title" data-tenant-nav-focus tabIndex={-1} className="mt-0.5 scroll-mt-24 font-serif text-xl font-medium tracking-tight focus:outline-none">Saved homes</h2>
            </div>
            {visibleSavedHomes.length > 0 && <p className="text-xs text-slate-500">A small collection, kept in one place</p>}
          </div>

          {savedHomesIdentityMatches && savedHomesState.status === 'error' && <div className="mb-3 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-200 bg-amber-50/90 px-3 py-2.5" role="alert">
            <p className="text-sm text-amber-950">Saved homes couldn’t be loaded right now.</p>
            <button type="button" onClick={() => setSavedHomesReload(value => value + 1)} className={`min-h-11 rounded-lg px-3 text-sm font-semibold text-emerald-900 underline underline-offset-2 ${focusClass}`}>Retry</button>
          </div>}

          {savedHomesIdentityMatches && savedHomesState.status === 'loading' && visibleSavedHomes.length === 0 && <div className="grid grid-cols-1 gap-3 md:grid-cols-2" role="status" aria-label="Loading saved homes">
            {Array.from({ length: Math.min(savedHomesVisibleLimit, 2) }, (_, index) => index).map(index => <div key={index} className="min-h-[9.5rem] rounded-[18px] border border-slate-200 bg-white motion-safe:animate-pulse" />)}
          </div>}

          {savedHomesIdentityMatches && savedHomesState.status === 'ready' && visibleSavedHomes.length === 0 && <div className="flex min-h-[4.25rem] items-center gap-3 rounded-2xl border border-[#e7eae3] bg-white/70 px-4 py-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[#edf4ee] text-emerald-800"><Heart size={19} aria-hidden="true" /></span>
            <div className="min-w-0"><p className="text-sm font-semibold text-slate-900">Save a home for later</p><p className="mt-0.5 text-sm text-slate-600">Heart homes you like and they’ll appear here.</p></div>
          </div>}

          {visibleSavedHomes.length > 0 && <motion.div id="tenant-saved-home-grid" layout={!reduceMotion}
            className={`grid min-w-0 gap-3 ${savedHomesExpanded ? 'grid-cols-1 md:grid-cols-2' : hasSavedHomesStack ? 'grid-cols-1 md:grid-cols-[minmax(15rem,1fr)_minmax(15rem,1fr)_minmax(9rem,.58fr)]' : 'grid-cols-1 md:grid-cols-2'}`}
            aria-label="Your saved homes">
            <AnimatePresence initial={false}>
              {presentedSavedHomes.map((property, index) => <motion.div key={property.id} layout={!reduceMotion}
                initial={reduceMotion ? false : { opacity: 0, y: 9 }} animate={{ opacity: 1, y: 0 }} exit={reduceMotion ? undefined : { opacity: 0, y: -5 }}
                transition={reduceMotion ? { duration: 0 } : { duration: 0.3, delay: Math.min(index, 5) * 0.055, ease: [0.16, 1, 0.3, 1] }}>
                <SavedHomeCard property={property} isFavorite={isPropertySaved(property.id)} favoriteStateReady={favoriteIsReadyFor(property.id)}
                  favoritePending={isFavoritePending(property.id)} onToggleFavorite={toggleFavorite}
                  onOpenQuickView={openQuickView} onRequestVisit={onRequestVisit} />
              </motion.div>)}
              {!savedHomesExpanded && hasSavedHomesStack && <motion.div key="saved-stack" layout={!reduceMotion}
                initial={reduceMotion ? false : { opacity: 0, scale: 0.98 }} animate={{ opacity: 1, scale: 1 }} exit={reduceMotion ? undefined : { opacity: 0, scale: 0.98 }}
                transition={reduceMotion ? { duration: 0 } : { duration: 0.3, ease: [0.16, 1, 0.3, 1] }}>
                <SavedStackCard properties={[...additionalSavedHomes, ...visibleSavedHomes]}
                  additionalCount={additionalSavedHomes.length} hasMorePages={Boolean(savedHomesState.hasMore)} expanded={false}
                  onExpand={() => setSavedHomesExpanded(true)} />
              </motion.div>}
            </AnimatePresence>
          </motion.div>}

          {visibleSavedHomes.length > 0 && savedHomesExpanded && <div className="mt-2 flex justify-end">
            <button type="button" onClick={() => setSavedHomesExpanded(false)} aria-controls="tenant-saved-home-grid" aria-expanded="true" className={`inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-sm font-semibold text-emerald-900 hover:bg-emerald-50 ${focusClass}`}>Show less <ChevronLeft size={15} aria-hidden="true" className="-rotate-90" /></button>
          </div>}

          {savedHomesIdentityMatches && savedHomesState.status === 'ready' && savedHomesState.hasMore && (savedHomesExpanded || visibleSavedHomes.length === 0) && <div className="mt-1 text-right">
            <button type="button" onClick={loadMoreSavedHomes} disabled={savedHomesState.loadingMore} className={`inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-sm font-semibold text-emerald-900 hover:bg-emerald-50 disabled:opacity-60 ${focusClass}`}>
              {savedHomesState.loadingMore ? <><RefreshCw size={15} className="motion-safe:animate-spin" aria-hidden="true" />Loading saved homes…</> : 'Show more saved homes'}
            </button>
          </div>}
        </section>

        <div className="mt-6 grid min-w-0 gap-7 sm:mt-7 lg:grid-cols-[minmax(18rem,24rem)_minmax(0,1fr)] lg:items-start lg:gap-6 xl:gap-7">
          <div style={{ '--tenant-discovery-rail-top': tenantDiscoveryRailTop } as React.CSSProperties}
            className={`tenant-discovery-rail ${view === 'populated' ? 'order-1' : 'order-2'} min-w-0 lg:order-1`}>
          <span id="tenant-discovery-collision-sentinel" aria-hidden="true" className="pointer-events-none absolute left-0 top-0 h-px w-px" />
          <details ref={visitHistoryDetailsRef} id="visit-history" className="group min-w-0 overflow-hidden rounded-[18px] border border-[#e3e5dc] bg-gradient-to-br from-[#fffefa] to-[#f5f8f1] shadow-[0_8px_24px_-21px_rgba(15,45,34,.5)]">
            <summary className={`flex min-h-[68px] cursor-pointer list-none items-center gap-3 px-3.5 py-2.5 marker:hidden [&::-webkit-details-marker]:hidden ${focusClass}`}>
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-[#d8e7dd] bg-[#edf5ef] text-emerald-800"><CalendarDays size={19} aria-hidden="true" /></span>
              <div className="min-w-0 flex-1">
                <h2 id="visit-history-title" data-tenant-nav-focus tabIndex={-1} onFocus={() => { if (visitHistoryDetailsRef.current) visitHistoryDetailsRef.current.open = true; }} className="scroll-mt-24 font-['Outfit'] text-sm font-semibold text-slate-950 focus:outline-none">Visit Requests</h2>
                <p className="mt-0.5 truncate text-xs text-slate-600">{tenantVisitSummary(view, visibleHistory.totalCount)}</p>
              </div>
              <ChevronRight size={17} className="shrink-0 text-emerald-800 transition-transform duration-200 group-open:rotate-90 motion-reduce:transition-none" aria-hidden="true" />
            </summary>
            <div className="space-y-3 border-t border-emerald-950/10 p-3">
            <section aria-labelledby="confirmed-visit-sessions-title" className="space-y-3 rounded-2xl border border-emerald-200/80 bg-emerald-50/60 p-3 sm:p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <h3 id="confirmed-visit-sessions-title" className="font-['Outfit'] text-sm font-semibold text-slate-950">Visit sessions</h3>
                  <p className="mt-1 text-xs leading-5 text-slate-600">Current schedule and visit progress from Pathome.</p>
                </div>
                {(visibleTenantSessions.status === 'error' || visibleTenantOutcomeHistory.status === 'error') && <button type="button" onClick={() => setTenantSessionsReload(value => value + 1)} className={`min-h-11 shrink-0 rounded-lg px-3 text-xs font-semibold text-emerald-900 underline ${focusClass}`}>Retry</button>}
              </div>
              {tenantSessionError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs text-rose-900">{tenantSessionError}</p>}
              {visibleTenantSessions.status === 'loading' && <p role="status" className="text-sm text-slate-600">Loading visit sessions…</p>}
              {visibleTenantSessions.status === 'error' && <p role="alert" className="text-sm text-slate-600">Visit session details are unavailable right now.</p>}
              {visibleTenantOutcomeHistory.status === 'loading' && visibleTenantSessions.status === 'ready' && <p role="status" className="text-xs text-slate-600">Loading visit outcome details…</p>}
              {visibleTenantOutcomeHistory.status === 'error' && <p role="alert" className="text-xs text-rose-800">Visit outcome details are unavailable right now. Retry to reload your visit history.</p>}
              {visibleTenantSessions.status === 'ready' && visibleTenantSessions.sessions.length === 0 && <p role="status" className="text-sm text-slate-600">Confirmed visit sessions will appear here.</p>}
              {visibleTenantSessions.status === 'ready' && visibleTenantSessions.sessions.map(visit => {
                const outcome = tenantOutcomesBySession.get(visit.sessionId);
                const code = tenantSessionCodes[visit.sessionId];
                const isPending = tenantSessionAction === visit.sessionId;
                const needsTenantConfirmation = visit.tenantConfirmationState === 'PENDING';
                const statusLabel = tenantVisitOutcomeStatusLabel(outcome, visit.status);
                return <article key={visit.sessionId} className="min-w-0 rounded-xl border border-emerald-100 bg-white p-3 shadow-sm sm:p-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-900">{statusLabel}</span>
                    {visit.repairState === 'PROPOSED' && <span className="rounded-full border border-sky-200 bg-sky-50 px-2.5 py-1 text-[11px] font-semibold text-sky-900">Proposed time</span>}
                    {visit.repairState === 'REQUIRED' && <span className="rounded-full border border-amber-200 bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-900">Action required</span>}
                  </div>
                  <p className="mt-2 text-sm font-semibold text-slate-950">{visit.status === 'INTERRUPTED' ? 'Visit interrupted; Operations is reviewing recovery' : visit.repairState === 'REQUIRED' ? 'Time under review' : visit.repairState === 'PROPOSED' ? 'Proposed visit time' : outcome?.outcomeReportAvailable ? tenantVisitOutcomeSummaryText(outcome) : visit.status === 'STARTED' ? 'Visit in progress' : visit.status === 'DRAFT' ? 'Visit time is being arranged' : visit.status === 'CANCELLED' ? 'Visit cancelled' : visit.status === 'NO_SHOW' ? 'Visit marked no-show' : visit.status === 'EXPIRED' ? 'Visit expired' : visit.status === 'COMPLETED' ? 'Visit ended; details pending' : 'Scheduled'}{visit.status !== 'INTERRUPTED' && visit.repairState !== 'REQUIRED' && visit.status !== 'DRAFT' && <> · {formatVisitTime(visit.scheduledAt, visit.zoneId)}</>}</p>
                  {outcome?.outcomeReportAvailable && <div className="mt-3 rounded-lg border border-emerald-100 bg-emerald-50/60 p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <p className="text-sm font-semibold text-slate-900">Visit outcomes</p>
                      {outcome.outcomeSummary && <span className="text-xs font-semibold text-emerald-900">{outcome.outcomeSummary === 'ALL_VIEWED' ? 'All properties viewed' : outcome.outcomeSummary === 'PARTLY_VIEWED' ? 'Some properties viewed' : outcome.outcomeSummary === 'NONE_VIEWED' ? 'No properties viewed' : 'Results not recorded'}</span>}
                    </div>
                    <p className="mt-1 text-xs leading-5 text-slate-700">{tenantVisitOutcomeSummaryText(outcome)}</p>
                    {outcome.properties.length > 0 && <details className="mt-2 rounded-lg border border-emerald-100 bg-white p-2.5">
                      <summary className={`min-h-11 cursor-pointer py-2 text-xs font-semibold text-emerald-900 ${focusClass}`}>View properties in this visit</summary>
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
                  {visit.arrivedAt && <p className="mt-1 text-xs text-slate-600">Ground Executive reported arrival at {formatVisitTime(visit.arrivedAt, visit.zoneId)}.</p>}
                  {visit.startedAt && <p className="mt-1 text-xs text-slate-600">Started {formatVisitTime(visit.startedAt, visit.zoneId)} · expected end {formatVisitTime(visit.expectedEndAt, visit.zoneId)}{visit.overPlannedTime ? ' · running over planned time' : ''}</p>}
                  {visit.tenantEtaAt && <p className="mt-1 text-xs text-slate-600">Your confirmed ETA: {formatVisitTime(visit.tenantEtaAt, visit.zoneId)}.</p>}
                  {visit.repairState === 'PROPOSED' && <p className="mt-2 rounded-lg bg-sky-50 p-2 text-xs leading-5 text-sky-950">This time is proposed and will be confirmed only after you accept it.</p>}
                  {visit.repairState === 'REQUIRED' && <p className="mt-2 rounded-lg bg-amber-50 p-2 text-xs leading-5 text-amber-950">Your earlier visit time can no longer be confirmed. Operations is arranging a safe option and will update you.</p>}
                  {visit.status === 'DRAFT' && visit.repairState === 'NONE' && <p className="mt-2 rounded-lg bg-slate-50 p-2 text-xs leading-5 text-slate-700">A visit time is not confirmed yet. You can request a start code after a time is scheduled.</p>}
                  {needsTenantConfirmation && (visit.repairState === 'NONE' || visit.repairState === 'PROPOSED') && <div className="mt-3 space-y-2">
                    <p className="text-xs leading-5 text-slate-700">{visit.repairState === 'PROPOSED' ? 'A safe visit time is proposed. Confirm it only if it works for you.' : 'Operations proposed a time change. Confirm it only if the proposed visit time works for you.'}</p>
                    <div className="flex flex-wrap gap-2">
                      <button type="button" disabled={isPending} onClick={() => void confirmVisitChange(visit.sessionId, 'ACCEPT_RESCHEDULE', visit.version)} className={`min-h-11 rounded-xl bg-emerald-700 px-4 text-xs font-semibold text-white hover:bg-emerald-800 disabled:opacity-60 ${focusClass}`}>Confirm proposed time</button>
                      <button type="button" disabled={isPending} onClick={() => void confirmVisitChange(visit.sessionId, 'REJECT_RESCHEDULE', visit.version)} className={`min-h-11 rounded-xl border border-slate-300 bg-white px-4 text-xs font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-60 ${focusClass}`}>I can’t make this time</button>
                    </div>
                  </div>}
                  {visit.status === 'PROVISIONAL_NO_SHOW' && <button type="button" disabled={isPending} onClick={() => void confirmVisitChange(visit.sessionId, 'DISPUTE_NO_SHOW', visit.version)} className={`mt-3 min-h-11 rounded-xl border border-amber-300 bg-amber-50 px-4 text-xs font-semibold text-amber-950 hover:bg-amber-100 disabled:opacity-60 ${focusClass}`}>Dispute provisional no-show</button>}
                  {visit.status === 'SCHEDULED' && !needsTenantConfirmation && visit.repairState === 'NONE' && <div className="mt-3">
                    {code ? <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-3" role="status" aria-live="polite">
                      <p className="text-xs font-semibold text-emerald-950">Your one-time visit start code</p>
                      <p className="mt-1 font-mono text-2xl font-bold tracking-[0.28em] text-emerald-900" aria-label="Visit start code">{code.code}</p>
                      <p className="mt-1 text-xs text-emerald-900">Share this code with your assigned Ground Executive. It expires {formatVisitTime(code.expiresAt)}. This code is shown only in your signed-in Pathome account.</p>
                      <button type="button" disabled={isPending} onClick={() => void issueVisitCode(visit.sessionId)} className={`mt-2 min-h-11 rounded-lg px-3 text-xs font-semibold text-emerald-900 underline disabled:opacity-50 ${focusClass}`}>Get a new code</button>
                    </div> : <button type="button" disabled={isPending} onClick={() => void issueVisitCode(visit.sessionId)} className={`min-h-11 rounded-xl bg-emerald-700 px-4 text-xs font-semibold text-white hover:bg-emerald-800 disabled:opacity-60 ${focusClass}`}>Get visit start code</button>}
                  </div>}
                </article>;
              })}
              {visibleTenantSessions.status === 'ready' && visibleTenantSessions.totalPages > 1 && <nav aria-label="Visit session pages" className="flex items-center justify-between gap-3 text-sm text-slate-700">
                <button type="button" disabled={tenantSessionPage === 0} onClick={() => setTenantSessionPage(value => value - 1)} className={`min-h-11 rounded-lg px-3 font-semibold disabled:opacity-50 ${focusClass}`}>Previous</button>
                <span>Page {tenantSessionPage + 1} of {visibleTenantSessions.totalPages}</span>
                <button type="button" disabled={tenantSessionPage + 1 >= visibleTenantSessions.totalPages} onClick={() => setTenantSessionPage(value => value + 1)} className={`min-h-11 rounded-lg px-3 font-semibold disabled:opacity-50 ${focusClass}`}>Next</button>
              </nav>}
            </section>
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
            </div>
          </details>
          <TenantQuickRefinePanel filters={searchFilters} discoveryCity={discoveryCity} discoveryState={discoveryState}
            discoveryLoadedKey={discoveryLoadedKey} homesLoaded={properties.length} rentBounds={quickRefineRentBounds}
            onSearchHomes={onSearchHomes} />
          </div>

          <section id="discover-homes" aria-labelledby="discover-homes-title" className={`${view === 'populated' ? 'order-2' : 'order-1'} min-w-0 scroll-mt-[calc(80px+env(safe-area-inset-top)+4.5rem)] lg:order-2`}>
            <div className="mb-4"><p className="text-xs font-bold uppercase tracking-[0.16em] text-emerald-800">Keep exploring</p>
              <h2 id="discover-homes-title" tabIndex={-1} className="mt-1 scroll-mt-[calc(80px+env(safe-area-inset-top)+4.5rem)] font-serif text-2xl font-medium tracking-tight focus:outline-none">Available homes</h2>
              <p className="mt-1 text-sm text-slate-600">Browse current listings and request a visit when you find a fit.</p>
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
            {discoveryState === 'READY' && properties.length > 0 && availableProperties.length === 0 && <p className="rounded-2xl border border-[#e7eae3] bg-white/70 px-4 py-3 text-sm text-slate-600">Homes you’ve saved from this search are shown above.</p>}
            {discoveryState === 'READY' && availableProperties.length > 0 && <div className="grid min-w-0 gap-4 sm:grid-cols-2">
              {availableProperties.map((property, index) => {
                const favoriteStateReady = favoriteIsReadyFor(property.id);
                return <SupportingPropertyCard key={property.id} property={property} onRequestVisit={onRequestVisit}
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
      </div>
    </main>
    {showTenantMobileDock && <motion.nav aria-label="Tenant quick navigation" initial={reduceMotion ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: mobileDockIsScrolling ? 4 : 0 }}
      transition={reduceMotion ? { duration: 0 } : { duration: mobileDockIsScrolling ? 0.2 : 0.24, ease: [0.16, 1, 0.3, 1] }}
      className={`fixed inset-x-3 bottom-[calc(0.5rem+env(safe-area-inset-bottom))] z-[95] isolate mx-auto flex h-[74px] w-[calc(100vw-24px)] max-w-[32rem] items-center gap-1 overflow-hidden rounded-[24px] border p-2 transition-[background-color,border-color,box-shadow,backdrop-filter] duration-200 motion-reduce:transition-none lg:hidden ${mobileDockIsScrolling
        ? 'pointer-events-none border-white/15 bg-[#fffdf8]/10 shadow-none backdrop-blur-[2px]'
        : 'border-[#ddd8ca] bg-[#fffdf8]/95 shadow-[0_12px_34px_-18px_rgba(5,40,29,.32),inset_0_1px_0_rgba(255,255,255,.92)] backdrop-blur-xl'} before:pointer-events-none before:absolute before:inset-x-10 before:top-0 before:h-px before:bg-gradient-to-r before:from-transparent before:via-[#c5a65d]/60 before:to-transparent ${mobileDockIsScrolling ? 'before:opacity-20' : ''}`}>
      {([
        { item: 'home', label: 'Home', icon: HomeIcon },
        { item: 'filters', label: 'Filters', icon: SlidersHorizontal },
        { item: 'visits', label: 'Visits', icon: CalendarDays },
        { item: 'saved', label: 'Saved', icon: Heart }
      ] as const).map(({ item, label, icon: Icon }) => {
        const active = mobileDockActiveItem === item;
        const badgeCount = item === 'filters' ? mobileDockBadges.filterCount
          : item === 'visits' ? mobileDockBadges.visitCount : null;
        return <button key={item} type="button"
          id={item === 'filters' ? 'tenant-mobile-quick-refine-trigger' : undefined}
          aria-label={item === 'filters' && badgeCount ? `Filters, ${badgeCount} active ${badgeCount === 1 ? 'filter' : 'filters'}` : label}
          aria-current={active ? 'location' : undefined}
          aria-haspopup={item === 'filters' ? 'dialog' : undefined}
          aria-expanded={item === 'filters' ? quickRefineSheetOpen : undefined}
          onClick={() => {
            if (item === 'filters') {
              setMobileDockActiveItem('filters');
              quickRefineOpenRef.current?.();
            } else navigateMobileDock(item);
          }}
          className={`relative flex h-full min-w-0 flex-1 flex-col items-center justify-center gap-0.5 rounded-[17px] px-1 text-[10px] font-semibold leading-none transition-[color,background-color,opacity] duration-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-800 motion-reduce:transition-none ${mobileDockIsScrolling ? 'pointer-events-none opacity-65' : ''} ${active ? mobileDockIsScrolling ? 'text-emerald-900' : 'bg-[#edf4e9] text-[#0a5f45]' : 'text-slate-700 hover:bg-white/75'}`}>
          {item === 'filters' ? <span className={`relative grid h-9 w-10 place-items-center rounded-xl ${mobileDockIsScrolling ? 'bg-transparent text-emerald-900 shadow-none' : 'bg-[#0b674b] text-[#fff8e8] shadow-[0_4px_10px_-7px_rgba(4,47,31,.65)]'}`}>
            <Icon size={18} strokeWidth={1.9} aria-hidden="true" />
            {badgeCount !== null && <span aria-hidden="true" className="absolute -right-1.5 -top-1.5 grid h-[17px] min-w-[17px] place-items-center rounded-full border border-[#fffdf8] bg-[#d7bc78] px-1 text-[9px] font-bold leading-none text-[#153c2e]">{badgeCount}</span>}
          </span> : <span className="relative grid h-9 w-10 place-items-center text-emerald-800"><Icon size={19} strokeWidth={1.8} aria-hidden="true" />
            {badgeCount !== null && <span aria-hidden="true" className="absolute -right-1 -top-1 grid h-[17px] min-w-[17px] place-items-center rounded-full bg-[#d7bc78] px-1 text-[9px] font-bold leading-none text-[#153c2e]">{badgeCount}</span>}
          </span>}
          <span className="truncate">{label}</span>
          {active && <span aria-hidden="true" className="absolute bottom-1 h-0.5 w-2.5 rounded-full bg-[#c5a65d]" />}
        </button>;
      })}
    </motion.nav>}
    {previewPropertyId !== null && <TenantPropertyQuickView key={previewPropertyId} propertyId={previewPropertyId}
      onClose={closeQuickView} onRequestVisit={onRequestVisit} onViewProperty={onViewProperty} />}
    </>
  );
};
