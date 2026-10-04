import React, { useState, useRef, useEffect, useLayoutEffect } from 'react';
import { flushSync } from 'react-dom';
import { ArrowRight, ChevronDown, MapPin, Search, X } from 'lucide-react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import {
  RentalSearchFilters,
  RentalSuggestion,
  PROPERTY_TYPE_LABELS,
  buildRentalSearchFilters,
  compactSearchDraftFromFilters,
  discoverySearchKey,
  formatCompactSearchContext,
  hasActiveSearchFilters,
  normalizeSearchText,
  resolveCompactSearchDraft,
  shouldSurfaceSuggestionFailure,
  suggestionFailureDiagnostic
} from '../utils/rentalSearch';
import { DiscoveryLocationDialog } from './DiscoveryLocationDialog';
import { propertyService } from '../services/propertyService';

export interface CompactSearchContextProps {
  city: string;
  filters: RentalSearchFilters;
  appearance?: 'context' | 'hero' | 'tenant-hero';
  alwaysEditing?: boolean;
  sticky?: boolean;
  stickyMode?: 'hero' | 'sticky' | 'beacon';
  beaconTop?: number | null;
  searchMorphActive?: boolean;
  beaconAcknowledgement?: number;
  heroRevealVersion?: number;
  onInteraction?: () => void;
  onEngagementChange?: (engaged: boolean) => void;
  onBeaconExpand?: () => void;
  searchPlaceholder?: string;
  submitLabel?: string;
  onSearch: (
    city?: string,
    sector?: string,
    filters?: Pick<RentalSearchFilters, 'q' | 'bhk' | 'propertyType' | 'furnishing' | 'minRent' | 'maxRent' | 'rentalOnly'>
  ) => void;
  onManualCityChange: (newCity: string) => void;
  onClearAll: () => void;
}

export const CompactSearchContext: React.FC<CompactSearchContextProps> = ({
  city,
  filters,
  appearance = 'context',
  alwaysEditing = false,
  sticky = false,
  stickyMode,
  beaconTop = null,
  searchMorphActive = false,
  beaconAcknowledgement = 0,
  heroRevealVersion = 0,
  onInteraction,
  onEngagementChange,
  onBeaconExpand,
  searchPlaceholder,
  submitLabel,
  onSearch,
  onManualCityChange,
  onClearAll
}) => {
  const [isLocationOpen, setIsLocationOpen] = useState(false);
  const [isEditing, setIsEditing] = useState(alwaysEditing);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [searchText, setSearchText] = useState(() => compactSearchDraftFromFilters(filters, alwaysEditing).text);
  const [searchTextIsSummary, setSearchTextIsSummary] = useState(() => compactSearchDraftFromFilters(filters, alwaysEditing).isDisplaySummary);
  const [suggestions, setSuggestions] = useState<RentalSuggestion[]>([]);
  const [suggestionState, setSuggestionState] = useState<'idle' | 'loading' | 'results' | 'empty' | 'error'>('idle');
  const [activeSuggestionIndex, setActiveSuggestionIndex] = useState(-1);
  const [inputFocused, setInputFocused] = useState(false);
  const [pointerInside, setPointerInside] = useState(false);
  const prefersReducedMotion = useReducedMotion() === true;

  const containerRef = useRef<HTMLDivElement>(null);
  const searchBarRef = useRef<HTMLDivElement>(null);
  const searchSurfaceRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const focusAfterBeaconRef = useRef(false);
  const suggestionListRef = useRef<HTMLDivElement>(null);
  const suggestionRequestRef = useRef(0);
  const committedFiltersKey = discoverySearchKey(filters);
  const lastCommittedFiltersRef = useRef(committedFiltersKey);
  const touchStartYRef = useRef<number | null>(null);
  const summary = formatCompactSearchContext(filters);
  const hasFilters = hasActiveSearchFilters(filters);
  const tenantHeroAppearance = appearance === 'tenant-hero';
  const heroAppearance = appearance === 'hero' || tenantHeroAppearance;
  const tenantSearchMode = stickyMode || (sticky ? 'sticky' : 'hero');
  const tenantBeacon = tenantHeroAppearance && tenantSearchMode === 'beacon';
  const tenantSticky = tenantHeroAppearance && tenantSearchMode === 'sticky';
  const containerClassName = tenantHeroAppearance
    ? tenantBeacon
      ? 'fixed left-3 right-auto top-[var(--tenant-beacon-top,44vh)] z-[95] h-12 w-12 border border-transparent lg:left-auto lg:right-[18px]'
      : tenantSticky
        ? 'fixed left-1/2 top-[calc(86px+env(safe-area-inset-top))] z-[90] mx-auto w-[calc(100vw-24px)] max-w-none -translate-x-1/2 rounded-[18px] border border-[#dce4d8]/90 bg-[#fffdf8]/95 p-1 shadow-[0_7px_18px_-8px_rgba(13,44,33,.2)] backdrop-blur-lg sm:w-[calc(100vw-48px)] sm:p-1.5 md:w-[calc(100vw-48px)] lg:top-[calc(88px+env(safe-area-inset-top))] lg:w-[min(64vw,650px)] lg:max-w-[650px] xl:w-[min(56.25vw,720px)] xl:max-w-[720px]'
        : 'relative z-50 w-full min-w-0 lg:max-w-[58rem]'
    : heroAppearance ? 'relative z-50 mx-auto w-full min-w-0' : 'relative mx-auto w-full max-w-[860px]';
  useLayoutEffect(() => {
    const surface = searchSurfaceRef.current;
    if (!surface) return;
    if (tenantBeacon) surface.setAttribute('inert', '');
    else surface.removeAttribute('inert');
  }, [tenantBeacon]);

  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    if (searchMorphActive) container.setAttribute('inert', '');
    else container.removeAttribute('inert');
  }, [searchMorphActive]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !tenantHeroAppearance || tenantSearchMode !== 'hero' || heroRevealVersion === 0 || prefersReducedMotion) return undefined;
    if (typeof container.animate !== 'function') return undefined;
    const animation = container.animate(
      [{ opacity: 0 }, { opacity: 1 }],
      { duration: 180, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' }
    );
    return () => animation.cancel();
  }, [heroRevealVersion, prefersReducedMotion, tenantHeroAppearance, tenantSearchMode]);

  useEffect(() => {
    if (searchMorphActive || !focusAfterBeaconRef.current) return;
    focusAfterBeaconRef.current = false;
    const frame = window.requestAnimationFrame(() => searchInputRef.current?.focus({ preventScroll: true }));
    return () => window.cancelAnimationFrame(frame);
  }, [searchMorphActive]);

  const notifyInteraction = () => onInteraction?.();

  useEffect(() => {
    onEngagementChange?.(inputFocused || pointerInside || isLocationOpen || (isEditing && showSuggestions));
  }, [inputFocused, pointerInside, isLocationOpen, isEditing, showSuggestions, onEngagementChange]);
  useEffect(() => () => onEngagementChange?.(false), [onEngagementChange]);

  // Sync draft text if external committed filters change
  useEffect(() => {
    if (lastCommittedFiltersRef.current !== committedFiltersKey) {
      lastCommittedFiltersRef.current = committedFiltersKey;
      const draft = compactSearchDraftFromFilters(filters, alwaysEditing);
      setSearchText(draft.text);
      setSearchTextIsSummary(draft.isDisplaySummary);
      setIsEditing(false);
      setShowSuggestions(false);
    }
  }, [committedFiltersKey, filters.q, summary, alwaysEditing]);

  // Click outside to close autocomplete suggestions & exit edit mode
  useEffect(() => {
    if (!isEditing && !showSuggestions) return;
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as Node;
      if (
        containerRef.current &&
        !containerRef.current.contains(target) &&
        (!suggestionListRef.current || !suggestionListRef.current.contains(target))
      ) {
        if (tenantHeroAppearance) setPointerInside(false);
        if (tenantHeroAppearance) searchInputRef.current?.blur();
        setIsEditing(false);
        setShowSuggestions(false);
        const draft = compactSearchDraftFromFilters(filters, alwaysEditing);
        setSearchText(draft.text);
        setSearchTextIsSummary(draft.isDisplaySummary);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isEditing, showSuggestions, filters.q, summary, alwaysEditing]);

  // Mobile page-scroll keyboard dismissal:
  // When in edit mode, detect real user page scroll gestures originating outside
  // the search bar and suggestion panel. Closes keyboard and returns to context mode.
  useEffect(() => {
    if (!isEditing) {
      touchStartYRef.current = null;
      return undefined;
    }

    const handleTouchStart = (e: TouchEvent) => {
      const target = e.target as Node;
      // Ignore touches starting inside the search bar or inside suggestions
      if (containerRef.current?.contains(target)) return;
      if (suggestionListRef.current?.contains(target)) return;
      if (e.touches && e.touches[0]) {
        touchStartYRef.current = e.touches[0].clientY;
      }
    };

    const handleTouchMove = (e: TouchEvent) => {
      if (touchStartYRef.current === null) return;
      if (!e.touches || !e.touches[0]) return;
      const currentY = e.touches[0].clientY;
      const deltaY = Math.abs(currentY - touchStartYRef.current);
      if (deltaY > 10) {
        // User is swiping/scrolling the discovery page outside!
        searchInputRef.current?.blur();
        setIsEditing(false);
        setShowSuggestions(false);
        const draft = compactSearchDraftFromFilters(filters, alwaysEditing);
        setSearchText(draft.text);
        setSearchTextIsSummary(draft.isDisplaySummary);
        touchStartYRef.current = null;
      }
    };

    const handleTouchEnd = () => {
      touchStartYRef.current = null;
    };

    const handleWheel = (e: WheelEvent) => {
      const target = e.target as Node;
      if (containerRef.current?.contains(target)) return;
      if (suggestionListRef.current?.contains(target)) return;
      searchInputRef.current?.blur();
      setIsEditing(false);
      setShowSuggestions(false);
      const draft = compactSearchDraftFromFilters(filters, alwaysEditing);
      setSearchText(draft.text);
      setSearchTextIsSummary(draft.isDisplaySummary);
    };

    document.addEventListener('touchstart', handleTouchStart, { passive: true });
    document.addEventListener('touchmove', handleTouchMove, { passive: true });
    document.addEventListener('touchend', handleTouchEnd, { passive: true });
    window.addEventListener('wheel', handleWheel, { passive: true });

    return () => {
      document.removeEventListener('touchstart', handleTouchStart);
      document.removeEventListener('touchmove', handleTouchMove);
      document.removeEventListener('touchend', handleTouchEnd);
      window.removeEventListener('wheel', handleWheel);
    };
  }, [isEditing, filters.q, summary, alwaysEditing]);

  // Fetch suggestions when user types in search input
  useEffect(() => {
    const query = normalizeSearchText(searchText);
    if (!isEditing || !showSuggestions || query.length < 2) {
      setSuggestionState('idle');
      setSuggestions([]);
      setActiveSuggestionIndex(-1);
      return undefined;
    }

    const requestId = ++suggestionRequestRef.current;
    const controller = new AbortController();
    setSuggestionState('loading');

    const timer = window.setTimeout(async () => {
      try {
        const items = await propertyService.fetchRentalSuggestions(query, city || undefined, controller.signal);
        if (requestId !== suggestionRequestRef.current || controller.signal.aborted) return;
        setSuggestions(items);
        setActiveSuggestionIndex(-1);
        setSuggestionState(items.length ? 'results' : 'empty');
      } catch (error) {
        if (!shouldSurfaceSuggestionFailure(error, controller.signal.aborted, requestId === suggestionRequestRef.current)) return;
        if (import.meta.env.DEV) console.warn('Rental suggestions request failed', suggestionFailureDiagnostic(error, query.length));
        setSuggestions([]);
        setSuggestionState('error');
      }
    }, 250);

    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [searchText, city, showSuggestions, isEditing]);

  const enterEditMode = () => {
    notifyInteraction();
    const draft = compactSearchDraftFromFilters(filters, true);
    flushSync(() => {
      setIsEditing(true);
      setSearchText(draft.text);
      setSearchTextIsSummary(draft.isDisplaySummary);
    });
    searchInputRef.current?.focus();
    if ((filters.q || summary || '').trim().length >= 2) {
      setShowSuggestions(true);
    }
  };

  const exitEditMode = () => {
    notifyInteraction();
    setIsEditing(false);
    setShowSuggestions(false);
    const draft = compactSearchDraftFromFilters(filters, true);
    setSearchText(draft.text);
    setSearchTextIsSummary(draft.isDisplaySummary);
    searchInputRef.current?.blur();
  };

  const handleCitySelect = (newCity: string) => {
    notifyInteraction();
    setIsLocationOpen(false);
    setIsEditing(false);
    setShowSuggestions(false);
    setSearchText('');
    setSearchTextIsSummary(false);
    lastCommittedFiltersRef.current = discoverySearchKey({ city: newCity, rentalOnly: true });
    onManualCityChange(newCity);
  };

  const handleClearAll = () => {
    setIsEditing(false);
    setShowSuggestions(false);
    setSuggestions([]);
    setSearchText('');
    setSearchTextIsSummary(false);
    lastCommittedFiltersRef.current = discoverySearchKey({ city, rentalOnly: true });
    onClearAll();
  };

  const chooseSuggestion = (item: RentalSuggestion) => {
    notifyInteraction();
    setIsEditing(false);
    setShowSuggestions(false);
    setSuggestions([]);
    searchInputRef.current?.blur();
    try {
      const rank = suggestions.findIndex(s => s === item);
      propertyService.reportSearchFeedback({
        eventType: 'SUGGESTION_SELECTED',
        candidateTerm: searchText.trim(),
        canonicalLocality: item.locality || undefined,
        canonicalCity: item.city || undefined,
        selectedType: item.type,
        selectedRank: rank >= 0 ? rank : undefined,
        bhkKey: item.bhk || undefined
      });
    } catch {
      // Non-blocking telemetry
    }
    const nextFilters = buildRentalSearchFilters(item.city, item.label, item);
    lastCommittedFiltersRef.current = discoverySearchKey(nextFilters);
    setSearchText(item.label);
    setSearchTextIsSummary(!nextFilters.q);
    onSearch(nextFilters.city, nextFilters.sector, nextFilters);
  };

  const applySearch = () => {
    notifyInteraction();
    setIsEditing(false);
    setShowSuggestions(false);
    setSuggestions([]);
    searchInputRef.current?.blur();
    const query = searchText.trim();
    if (searchTextIsSummary) {
      const nextFilters = resolveCompactSearchDraft(filters, { text: query, isDisplaySummary: true });
      lastCommittedFiltersRef.current = discoverySearchKey(nextFilters);
      onSearch(nextFilters.city, nextFilters.sector, nextFilters);
      return;
    }
    if (!query) {
      handleClearAll();
      return;
    }
    const nextFilters = resolveCompactSearchDraft(filters, { text: query, isDisplaySummary: false });
    lastCommittedFiltersRef.current = discoverySearchKey(nextFilters);
    onSearch(nextFilters.city, nextFilters.sector, nextFilters);
  };

  const formatSuggestionRow = (item: RentalSuggestion) => {
    if (item.type === 'SEARCH_QUERY' && item.locality) {
      const secondaryParts: string[] = [];
      if (item.bhk) secondaryParts.push(item.bhk);
      if (item.propertyType && PROPERTY_TYPE_LABELS[item.propertyType]) {
        secondaryParts.push(PROPERTY_TYPE_LABELS[item.propertyType]);
      }
      if (item.city) secondaryParts.push(item.city);

      return {
        primary: item.locality,
        secondary: secondaryParts.length > 0 ? secondaryParts.join(' · ') : (item.city || 'Rental home'),
        icon: <MapPin className="h-4 w-4 shrink-0 text-emerald-400" aria-hidden="true" />,
        badge: item.resultCount !== null ? `${item.resultCount} ${item.resultCount === 1 ? 'home' : 'homes'}` : null,
        isAction: false
      };
    }

    if (item.type === 'LOCALITY' || item.type === 'ENTITY_MATCH') {
      return {
        primary: item.locality || item.label,
        secondary: item.city ? `Locality · ${item.city}` : 'Locality',
        icon: <MapPin className="h-4 w-4 shrink-0 text-emerald-400" aria-hidden="true" />,
        badge: item.resultCount !== null ? `${item.resultCount} ${item.resultCount === 1 ? 'home' : 'homes'}` : null,
        isAction: false
      };
    }

    if (item.type === 'CITY') {
      return {
        primary: item.city || item.label,
        secondary: 'City',
        icon: <MapPin className="h-4 w-4 shrink-0 text-emerald-400" aria-hidden="true" />,
        badge: item.resultCount !== null ? `${item.resultCount} ${item.resultCount === 1 ? 'home' : 'homes'}` : null,
        isAction: false
      };
    }

    if (item.type === 'SEARCH_ANYWAY') {
      return {
        primary: item.label.startsWith('Search ') ? item.label : `Search “${item.label}”`,
        secondary: 'Search all listings',
        icon: <Search className="h-4 w-4 shrink-0 text-emerald-400" aria-hidden="true" />,
        badge: 'Search all',
        isAction: true
      };
    }

    if (item.type === 'QUERY_INTENT') {
      return {
        primary: item.label,
        secondary: 'Search this requirement',
        icon: <Search className="h-4 w-4 shrink-0 text-emerald-400" aria-hidden="true" />,
        badge: null,
        isAction: false
      };
    }

    if (item.type === 'UNSUPPORTED_CITY') {
      return {
        primary: item.label,
        secondary: 'City currently unavailable',
        icon: <MapPin className="h-4 w-4 shrink-0 text-amber-500" aria-hidden="true" />,
        badge: null,
        isAction: false
      };
    }

    return {
      primary: item.label,
      secondary: item.city || undefined,
      icon: <Search className="h-4 w-4 shrink-0 text-slate-400" aria-hidden="true" />,
      badge: item.resultCount !== null ? `${item.resultCount} ${item.resultCount === 1 ? 'home' : 'homes'}` : null,
      isAction: false
    };
  };

  const clearDraftButton = searchText ? (
    <button
      type="button"
      id="compact-clear-draft"
      aria-label={tenantHeroAppearance ? 'Clear search and filters' : 'Clear search text'}
      onClick={() => {
        notifyInteraction();
        if (tenantHeroAppearance) {
          searchInputRef.current?.focus({ preventScroll: true });
          handleClearAll();
          return;
        }
        setSearchText('');
        setSearchTextIsSummary(false);
        searchInputRef.current?.focus();
        setShowSuggestions(false);
      }}
      className={tenantHeroAppearance
        ? 'grid h-11 w-11 shrink-0 place-items-center bg-transparent p-0 leading-none text-slate-500 transition-colors hover:text-slate-900 focus-visible:outline-none focus-visible:text-emerald-800 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-emerald-700/60'
        : heroAppearance
          ? 'flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600'
          : 'flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-slate-400 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400'}
    >
      <X className="block h-3.5 w-3.5" aria-hidden="true" />
    </button>
  ) : null;

  const submitButton = (
    <button
      type="button"
      id="compact-search-submit"
      aria-label={tenantHeroAppearance ? 'Explore homes' : (submitLabel || 'Submit search')}
      onClick={applySearch}
      className={heroAppearance
        ? tenantHeroAppearance
          ? tenantSticky
            ? 'grid h-11 w-11 min-h-11 shrink-0 place-items-center rounded-none border-0 bg-transparent p-0 leading-none text-emerald-700 shadow-none transition-[color,transform] duration-150 hover:translate-x-0.5 hover:text-emerald-900 focus-visible:translate-x-0.5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-5px] focus-visible:outline-emerald-700/55 active:translate-x-0.5 motion-reduce:transition-none motion-reduce:hover:translate-x-0 motion-reduce:focus-visible:translate-x-0'
            : 'grid h-11 w-11 min-h-11 shrink-0 place-items-center border-0 bg-transparent p-0 leading-none text-emerald-700 shadow-none transition-[color,transform] duration-150 hover:translate-x-0.5 hover:text-emerald-800 focus-visible:translate-x-0.5 focus-visible:outline-none focus-visible:text-emerald-950 focus-visible:drop-shadow-[0_0_5px_rgba(8,123,96,0.5)] active:translate-x-0.5 motion-reduce:transition-none motion-reduce:hover:translate-x-0 motion-reduce:focus-visible:translate-x-0'
          : `inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-emerald-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 ${submitLabel ? 'w-full sm:w-auto' : 'w-11'}`
        : 'flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-emerald-600 text-white shadow-sm transition-all hover:bg-emerald-500 active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400'}
    >
      {submitLabel && <span>{submitLabel}</span>}
      <ArrowRight
        className={tenantHeroAppearance ? tenantSticky ? 'block h-6 w-6 drop-shadow-[0_1px_2px_rgba(8,123,96,0.16)]' : 'block h-[1.125rem] w-[1.125rem] drop-shadow-[0_1px_2px_rgba(8,123,96,0.18)]' : 'block h-4 w-4'}
        strokeWidth={tenantHeroAppearance ? 1.8 : 2}
        aria-hidden="true"
      />
    </button>
  );

  return (
    <>
      {/* FLOATING SEARCH WRAPPER — Outer relative container determining exact matched width */}
      <motion.div
        animate={tenantHeroAppearance ? {
          borderRadius: tenantBeacon ? '999px' : '18px',
          backgroundColor: tenantSticky ? 'rgba(255,253,248,.95)' : 'rgba(255,253,248,0)',
          borderColor: tenantSticky ? 'rgba(220,228,216,.9)' : 'rgba(255,255,255,0)',
          boxShadow: tenantSticky ? '0 7px 18px -8px rgba(13,44,33,.2)' : '0 0 0 rgba(0,0,0,0)'
        } : undefined}
        ref={containerRef}
        id="compact-discovery-context"
        aria-hidden={searchMorphActive || undefined}
        style={{
          ...(tenantHeroAppearance ? {
            '--tenant-beacon-top': tenantBeacon && beaconTop !== null ? `${beaconTop}px` : undefined
          } as React.CSSProperties : {})
        }}
        className={`${containerClassName}${searchMorphActive ? ' invisible pointer-events-none' : ''}`}
        onPointerEnter={event => {
          if (tenantHeroAppearance && event.pointerType !== 'touch') {
            setPointerInside(true);
            if (tenantSticky) notifyInteraction();
          }
        }}
        onPointerLeave={() => {
          setPointerInside(false);
        }}
        onFocusCapture={() => {
          setInputFocused(true);
          notifyInteraction();
        }}
        onBlurCapture={event => {
          const nextTarget = event.relatedTarget as Node | null;
          if (nextTarget && (containerRef.current?.contains(nextTarget) || suggestionListRef.current?.contains(nextTarget))) return;
          setInputFocused(false);
          notifyInteraction();
        }}
      >
        <AnimatePresence initial={false} mode="sync" onExitComplete={() => {
          if (!focusAfterBeaconRef.current) return;
          if (searchMorphActive) return;
          focusAfterBeaconRef.current = false;
          window.requestAnimationFrame(() => searchInputRef.current?.focus({ preventScroll: true }));
        }}>
        {tenantBeacon ? <motion.button
          key="tenant-search-beacon"
          type="button"
          aria-label="Reopen home search"
          title="Reopen home search"
          aria-hidden={!tenantBeacon || undefined}
          disabled={!tenantBeacon || searchMorphActive}
          onClick={() => {
            notifyInteraction();
            focusAfterBeaconRef.current = true;
            onBeaconExpand?.();
          }}
          initial={prefersReducedMotion ? false : { opacity: 0, scale: 0.84 }}
          animate={{ opacity: 1, scale: 1 }}
          whileHover={prefersReducedMotion ? undefined : { scale: 1.04 }}
          exit={prefersReducedMotion ? { opacity: 0 } : { opacity: 0, scale: 0.92 }}
          transition={{ duration: prefersReducedMotion ? 0.1 : 0.24, ease: [0.22, 1, 0.36, 1] }}
          className="group/beacon relative isolate grid h-full w-full place-items-center rounded-full border border-white/75 bg-white/86 text-emerald-800 shadow-[0_5px_20px_rgba(9,40,29,.15)] backdrop-blur-md transition-[box-shadow,background-color] duration-300 hover:bg-white/95 hover:shadow-[0_7px_23px_rgba(9,40,29,.19)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-3 focus-visible:outline-emerald-700 motion-reduce:transform-none motion-reduce:transition-none"
        ><span aria-hidden="true" className="tenant-search-beacon-halo pointer-events-none absolute -inset-1 rounded-full bg-emerald-400/20 blur-md transition-opacity duration-300 group-hover/beacon:opacity-80" />
          {beaconAcknowledgement > 0 && <motion.span key={beaconAcknowledgement} aria-hidden="true" initial={{ opacity: 0, scale: 0.72 }} animate={{ opacity: [0, 0.22, 0], scale: [0.72, 1.25, 1.65] }} transition={{ duration: 0.3, ease: 'easeOut' }} className="pointer-events-none absolute -inset-1 rounded-full border border-emerald-500/45" />}
          {tenantHeroAppearance ? <span data-tenant-search-mode-icon="beacon" className="relative z-10 grid h-[22px] w-[22px] place-items-center"><Search className="block h-[22px] w-[22px]" strokeWidth={1.8} aria-hidden="true" /></span> : <Search className="relative z-10 block h-[22px] w-[22px]" strokeWidth={1.8} aria-hidden="true" />}
        </motion.button>
          : <motion.div ref={searchSurfaceRef} key="tenant-search-surface" aria-hidden={tenantBeacon || undefined} initial={false} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: prefersReducedMotion ? 0.08 : 0.14, ease: [0.22, 1, 0.36, 1] }}>
        {/* 1. FLOATING SEARCH BAR (Single compact row on both desktop & mobile) */}
        <div
          ref={searchBarRef}
          id="compact-search-bar"
            className={tenantHeroAppearance
            ? tenantSticky
                ? `flex w-full min-w-0 items-center gap-0 rounded-[14px] border-0 bg-transparent p-0 text-slate-900 shadow-none ${isEditing ? 'ring-2 ring-emerald-700/10' : ''}`
                : `grid w-full min-w-0 grid-cols-2 gap-x-1 gap-y-1 rounded-[10px] border border-[#e9e7e1] bg-white p-1 text-slate-900 shadow-[0_8px_22px_-16px_rgba(38,48,39,.28)] sm:grid-cols-1 sm:gap-0 lg:rounded-[10px] ${isEditing ? 'ring-2 ring-[#7b9b84]/30' : ''}`
              : heroAppearance
                ? `grid w-full min-w-0 grid-cols-1 gap-2 rounded-[18px] border border-white/80 bg-white p-1.5 text-slate-900 shadow-[0_16px_36px_rgba(4,26,19,.22)] ${isEditing ? 'ring-2 ring-white/35' : ''}`
                : `w-full rounded-xl border bg-slate-950/80 backdrop-blur-md backdrop-saturate-150 p-1 shadow-[0_8px_28px_rgb(0,0,0,0.24)] ring-1 ring-white/10 text-white transition-all sm:rounded-2xl sm:backdrop-blur-xl sm:p-2 sm:shadow-[0_12px_40px_rgb(0,0,0,0.3)] ${
            isEditing ? 'border-emerald-500/50 ring-2 ring-emerald-500/20' : 'border-white/15'
          }`}
        >
          <div className={tenantHeroAppearance
            ? tenantSticky
              ? 'grid h-10 min-w-0 flex-1 grid-cols-[minmax(4rem,0.58fr)_minmax(0,1.42fr)] items-center gap-x-1 md:grid-cols-[minmax(135px,155px)_minmax(0,1fr)] max-[360px]:grid-cols-[4.5rem_minmax(0,1fr)]'
              : 'col-span-2 grid min-w-0 grid-cols-[minmax(4.75rem,0.58fr)_minmax(0,1.42fr)] gap-x-1 max-[360px]:grid-cols-[68px_minmax(0,1fr)] sm:col-span-1 sm:grid-cols-[minmax(0,8rem)_minmax(0,1fr)] sm:items-center sm:gap-0'
            : heroAppearance
              ? 'grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-[minmax(0,9rem)_minmax(0,1fr)] sm:items-center'
              : 'flex h-10 w-full min-w-0 items-center gap-1.5 sm:gap-2'}>
            {/* City Selector */}
            <div className={tenantHeroAppearance ? 'flex min-w-0 items-center' : 'contents'}>
            <button
              type="button"
              id="compact-city-selector"
              onClick={() => {
                notifyInteraction();
                setIsEditing(false);
                setShowSuggestions(false);
                setIsLocationOpen(true);
              }}
              aria-haspopup="dialog"
              aria-label={`Current city: ${city || 'Choose city'}. Click to change.`}
              className={tenantHeroAppearance
                ? tenantSticky
                  ? 'group inline-flex h-11 min-w-0 flex-1 items-center justify-start rounded-lg border-0 px-2 pr-3 text-left text-slate-800 transition-colors hover:bg-emerald-50/45 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-emerald-600 max-[360px]:px-1.5 max-[360px]:pr-1.5'
                  : 'group inline-flex h-12 min-w-0 flex-1 items-center justify-end rounded-lg border-0 px-2 pr-4 text-left text-slate-800 transition-colors hover:bg-emerald-50/45 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-emerald-600 max-[360px]:px-1.5 max-[360px]:pr-1.5 sm:h-11 sm:px-2.5 sm:pr-4'
                : heroAppearance
                  ? 'group inline-flex min-h-11 w-full min-w-0 items-center gap-2 rounded-xl border border-slate-200/80 bg-[#f8faf7] px-3 text-left text-slate-800 transition-colors hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600'
                : 'group inline-flex h-9 sm:h-10 shrink-0 items-center gap-1.5 sm:gap-2 rounded-xl px-2 sm:px-3 text-left transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400'}
            >
              <span className={tenantHeroAppearance
                ? tenantSticky
                  ? 'flex min-w-0 items-center gap-1 sm:gap-2 max-[360px]:gap-1'
                  : 'flex min-w-0 items-center gap-1 sm:gap-2.5 max-[360px]:gap-1'
                : 'contents'}>
                {tenantHeroAppearance
                  ? <MapPin className="hidden h-3.5 w-3.5 shrink-0 text-emerald-700 transition-colors group-hover:text-emerald-800 min-[361px]:block" strokeWidth={1.8} aria-hidden="true" />
                  : <MapPin className={`h-4 w-4 shrink-0 ${heroAppearance ? 'text-emerald-700' : 'text-emerald-400 transition-colors group-hover:text-emerald-300'}`} aria-hidden="true" />}
                <span className={`${tenantHeroAppearance ? 'min-w-0 max-w-[140px] font-semibold leading-none text-slate-800 sm:text-[13px]' : `${heroAppearance ? 'min-w-0 flex-1 text-slate-800' : 'max-w-[80px] sm:max-w-[140px] text-white'} font-bold sm:text-sm`} truncate text-xs tracking-tight`}>
                  {city || 'Choose city'}
                </span>
                <ChevronDown className={`shrink-0 ${tenantHeroAppearance ? 'h-3 w-3 text-slate-400 transition-colors group-hover:text-emerald-700' : `h-3.5 w-3.5 ${heroAppearance ? 'text-slate-500' : 'text-slate-400 transition-transform group-hover:translate-y-0.5 group-hover:text-white'}`}`} aria-hidden="true" />
              </span>
            </button>
            {tenantHeroAppearance && <span className="h-5 w-px shrink-0 self-center bg-slate-300/55" aria-hidden="true" />}
            </div>

            {/* Faint Divider */}
            {!heroAppearance && <div className="h-5 w-px shrink-0 bg-white/15" aria-hidden="true" />}

            {/* CONDITIONAL CONTROLS: CONTEXT MODE vs EDIT MODE */}
            <div className={tenantHeroAppearance ? 'relative min-w-0' : 'contents'}>
            {!isEditing && !alwaysEditing ? (
              /* CONTEXT MODE (Committed filter summary or clean placeholder) */
              <div className={`flex ${tenantHeroAppearance ? tenantSticky ? 'h-10' : 'h-9 sm:h-10' : 'h-9 sm:h-10'} flex-1 min-w-0 items-center gap-1.5 sm:gap-2 px-1 sm:px-1.5`}>
                <button
                  type="button"
                  id="compact-search-trigger"
                  onClick={enterEditMode}
                  aria-label={hasFilters ? `Current filter: ${summary}. Click to edit search.` : 'Search locality or 2 BHK'}
                  className="flex h-full flex-1 min-w-0 items-center gap-1.5 sm:gap-2 rounded-xl text-left transition-colors hover:bg-white/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                >
                  <Search className={`h-4 w-4 shrink-0 ${hasFilters ? 'text-emerald-400' : 'text-slate-400'}`} aria-hidden="true" />
                  <span className="min-w-0 flex-1 truncate text-xs sm:text-sm font-medium text-white/90">
                    {summary ? (
                      summary
                    ) : (
                      <>
                        <span className="sm:hidden text-slate-400 font-normal">Search homes</span>
                        <span className="hidden sm:inline text-slate-400 font-normal">Search locality or 2 BHK</span>
                      </>
                    )}
                  </span>
                </button>

                {/* Clear all (when active committed filters exist) - Context Mode Only */}
                {hasFilters && (
                  <button
                    type="button"
                    id="compact-clear-all"
                    onClick={handleClearAll}
                    aria-label="Clear all filters"
                    className="inline-flex h-8 shrink-0 items-center rounded-lg px-2 text-xs font-semibold text-slate-300 transition-colors hover:bg-white/10 hover:text-emerald-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                  >
                    Clear all
                  </button>
                )}
              </div>
            ) : (
              /* EDIT MODE (Real input, clear draft text button, submit arrow; Clear all is strictly hidden) */
              <div className={heroAppearance
              ? tenantHeroAppearance
              ? tenantSticky
                  ? 'relative flex h-10 min-h-10 min-w-0 items-center gap-x-1 rounded-lg px-1 text-slate-900'
                  : `relative grid h-12 min-h-12 min-w-0 ${searchText ? 'grid-cols-[auto_minmax(0,1fr)_5.5rem]' : 'grid-cols-[auto_minmax(0,1fr)_2.75rem]'} items-center gap-x-1 rounded-full px-1 pb-0 pt-0 text-slate-900 max-[360px]:gap-x-0 max-[360px]:px-0 sm:grid-cols-[auto_minmax(0,1fr)_5.5rem] sm:h-11 sm:min-h-11 sm:pb-0 sm:pt-0 sm:gap-x-1 sm:px-1 lg:rounded-lg`
                : 'relative grid min-h-11 min-w-0 grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-2 gap-y-2 rounded-xl border border-slate-200 bg-white px-3 py-1 text-slate-900 focus-within:ring-2 focus-within:ring-emerald-600 sm:flex sm:items-center sm:py-0'
                : 'flex h-9 sm:h-10 flex-1 min-w-0 items-center gap-1.5 sm:gap-2 px-1 sm:px-1.5'}>
                {tenantHeroAppearance
                  ? <span data-tenant-search-mode-icon="sticky" className="grid h-4 w-4 shrink-0 place-items-center max-[360px]:hidden"><Search className="h-4 w-4 text-emerald-700" aria-hidden="true" /></span>
                  : <Search className={`h-4 w-4 shrink-0 ${heroAppearance ? 'text-emerald-700' : 'text-emerald-400'}`} aria-hidden="true" />}
                <input
                  ref={searchInputRef}
                  id="compact-search-input"
                  type="text"
                  role="combobox"
                  aria-autocomplete="list"
                  aria-expanded={showSuggestions && suggestionState !== 'idle'}
                  aria-controls="compact-search-suggestions"
                  aria-activedescendant={activeSuggestionIndex >= 0 ? `compact-opt-${activeSuggestionIndex}` : undefined}
                  value={searchText}
                  onFocus={() => {
                    notifyInteraction();
                    setInputFocused(true);
                    setIsEditing(true);
                    if (searchText.trim().length >= 2) setShowSuggestions(true);
                  }}
                  onBlur={(event) => {
                    const nextTarget = event.relatedTarget as Node | null;
                    if (nextTarget && (containerRef.current?.contains(nextTarget)
                      || suggestionListRef.current?.contains(nextTarget))) return;
                    setIsEditing(false);
                    setShowSuggestions(false);
                  }}
                  onChange={(e) => {
                    notifyInteraction();
                    setSearchText(e.target.value);
                    setSearchTextIsSummary(false);
                    setShowSuggestions(true);
                  }}
                  onKeyDown={(e) => {
                    notifyInteraction();
                    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                      if (suggestions.length) {
                        e.preventDefault();
                        setActiveSuggestionIndex((prev) =>
                          e.key === 'ArrowDown'
                            ? (prev + 1) % suggestions.length
                            : (prev <= 0 ? suggestions.length - 1 : prev - 1)
                        );
                      }
                    } else if (e.key === 'Enter') {
                      e.preventDefault();
                      if (activeSuggestionIndex >= 0 && suggestions[activeSuggestionIndex]) {
                        chooseSuggestion(suggestions[activeSuggestionIndex]);
                      } else {
                        applySearch();
                      }
                    } else if (e.key === 'Escape') {
                      e.preventDefault();
                      exitEditMode();
                    }
                  }}
                  placeholder={searchPlaceholder || summary || "Search locality or 2 BHK"}
                  aria-label="Search homes"
                  autoComplete="off"
                  className={`min-w-0 flex-1 bg-transparent text-base font-medium outline-none ${tenantHeroAppearance ? 'sm:text-xs' : ''} ${heroAppearance ? 'text-[#252b25] placeholder:text-[#85877f]' : 'text-white placeholder:text-slate-400'}`}
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck="false"
                />

                {tenantHeroAppearance
                  ? <div role="group" aria-label="Search actions" className={`grid h-11 shrink-0 place-items-center ${searchText ? 'w-[5.5rem] grid-cols-2' : 'w-11 grid-cols-1'} sm:w-[5.5rem] sm:grid-cols-2`}>
                      {searchText && <span className="grid h-11 w-11 place-items-center">{clearDraftButton}</span>}
                      <span className="grid h-11 w-11 place-items-center">{submitButton}</span>
                    </div>
                  : <>{clearDraftButton}{submitButton}</>}
              </div>
            )}
            </div>
          </div>
        </div>

        {/* 2. AUTOCOMPLETE PANEL — Attached directly to outer floating wrapper!
            Has EXACT SAME left, right, and width as the complete outer floating bar (tolerance <= 1px) */}
        {isEditing && showSuggestions && suggestionState !== 'idle' && (
          <div
            ref={suggestionListRef}
            id="compact-search-suggestions"
            role="listbox"
            aria-label="Rental search suggestions"
            className={tenantHeroAppearance
              ? 'absolute left-0 right-0 top-[calc(100%+0.5rem)] z-[70] max-h-[min(20rem,45dvh)] w-full overflow-y-auto overscroll-contain rounded-[8px] border border-[#e9e7e1] bg-white p-1.5 text-slate-900 shadow-[0_12px_28px_rgba(38,48,39,.13)] sm:left-[10rem] sm:right-0 sm:w-auto'
              : heroAppearance
                ? 'absolute left-0 right-0 top-[calc(100%+0.5rem)] z-[70] max-h-[min(20rem,45dvh)] w-full overflow-y-auto overscroll-contain rounded-2xl border border-slate-200 bg-white p-1.5 text-slate-900 shadow-2xl'
              : 'absolute left-0 right-0 top-[calc(100%+8px)] z-50 w-full max-h-[260px] sm:max-h-[300px] overflow-y-auto overscroll-contain rounded-2xl border border-slate-700/80 bg-slate-900/95 backdrop-blur-xl p-1.5 text-white shadow-[0_16px_50px_rgb(0,0,0,0.5)] ring-1 ring-white/10 divide-y divide-slate-800/60'}
          >
            {tenantHeroAppearance && <div aria-hidden="true" className="px-2 pb-1 pt-1 text-[9px] font-bold tracking-[0.14em] text-slate-500">SEARCH SUGGESTIONS</div>}
            {suggestionState === 'loading' && (
              <div className={`flex items-center gap-2.5 px-3 py-3 text-xs ${heroAppearance ? 'text-[#85877f]' : 'text-slate-400'}`}>
                <div className="h-3 w-3 animate-spin rounded-full border-2 border-emerald-500 border-t-transparent" />
                <span>Finding places…</span>
              </div>
            )}
            {suggestionState === 'empty' && (
              <p role="status" className={`px-3 py-3 text-xs ${heroAppearance ? 'text-[#85877f]' : 'text-slate-400'}`}>No matching homes found.</p>
            )}
            {suggestionState === 'error' && (
              <p role="status" className={`px-3 py-3 text-xs ${heroAppearance ? 'text-[#85877f]' : 'text-slate-400'}`}>Suggestions temporarily unavailable.</p>
            )}
            {suggestionState === 'results' && suggestions.map((item, index) => {
              const row = formatSuggestionRow(item);
              return (
                <button
                  key={`${item.type}-${item.city}-${item.locality || ''}-${item.bhk || ''}-${index}`}
                  id={`compact-opt-${index}`}
                  type="button"
                  role="option"
                  aria-selected={index === activeSuggestionIndex}
                  tabIndex={-1}
                  onMouseDown={(e) => e.preventDefault()}
                  onMouseEnter={notifyInteraction}
                  onFocus={notifyInteraction}
                  onClick={() => chooseSuggestion(item)}
                  className={`flex min-h-[48px] w-full min-w-0 items-center justify-between gap-2.5 rounded-[5px] px-3 py-2 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 ${heroAppearance ? 'focus-visible:ring-[#7b9b84]' : 'focus-visible:ring-emerald-500'} ${
                    index === activeSuggestionIndex
                      ? heroAppearance ? 'bg-[#f0f3ee] text-[#355c49] ring-1 ring-[#dfe7dc]' : 'bg-emerald-950/80 text-white ring-1 ring-emerald-500/30'
                      : row.isAction
                      ? heroAppearance ? 'bg-[#f8f7f4] text-[#252b25] hover:bg-[#f0f3ee]' : 'bg-slate-800/50 hover:bg-slate-800 text-white'
                      : heroAppearance ? 'text-[#252b25] hover:bg-[#f3f5f0]' : 'hover:bg-slate-800/70 text-white'
                  }`}
                >
                  <div className={`flex min-w-0 items-center gap-2.5 ${heroAppearance ? '[&_svg]:!text-[#638267]' : ''}`}>
                    {row.icon}
                    <div className="min-w-0">
                      <span className={`block truncate text-xs sm:text-sm font-semibold leading-snug ${heroAppearance ? 'text-[#252b25]' : 'text-white'}`}>
                        {row.primary}
                      </span>
                      {row.secondary && (
                        <span className={`block truncate text-[11px] font-normal leading-tight mt-0.5 ${heroAppearance ? 'text-[#85877f]' : 'text-slate-400'}`}>
                          {row.secondary}
                        </span>
                      )}
                    </div>
                  </div>

                  {row.badge && (
                    <span className={`shrink-0 pl-2 text-[11px] font-normal ${heroAppearance ? 'text-[#85877f]' : 'text-slate-400'}`}>
                      {row.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        )}
        </motion.div>}
        </AnimatePresence>
      </motion.div>

      {/* Shared City Dialog */}
      <DiscoveryLocationDialog
        open={isLocationOpen}
        step="city"
        onStepChange={() => {}}
        city={city}
        locality=""
        cityOnly
        onCityChange={handleCitySelect}
        onCitySelected={() => { notifyInteraction(); setIsLocationOpen(false); }}
        onLocalityChange={() => {}}
        onApply={() => {}}
        onClose={() => { notifyInteraction(); setIsLocationOpen(false); }}
      />
    </>
  );
};
