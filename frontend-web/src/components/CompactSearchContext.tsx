import React, { useState, useRef, useEffect } from 'react';
import { flushSync } from 'react-dom';
import { ArrowRight, ChevronDown, MapPin, Search, X } from 'lucide-react';
import {
  RentalSearchFilters,
  RentalSuggestion,
  PROPERTY_TYPE_LABELS,
  buildRentalSearchFilters,
  formatCompactSearchContext,
  hasActiveSearchFilters,
  normalizeSearchText,
  shouldSurfaceSuggestionFailure,
  suggestionFailureDiagnostic
} from '../utils/rentalSearch';
import { DiscoveryLocationDialog } from './DiscoveryLocationDialog';
import { propertyService } from '../services/propertyService';

export interface CompactSearchContextProps {
  city: string;
  filters: RentalSearchFilters;
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
  onSearch,
  onManualCityChange,
  onClearAll
}) => {
  const [isLocationOpen, setIsLocationOpen] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [searchText, setSearchText] = useState('');
  const [suggestions, setSuggestions] = useState<RentalSuggestion[]>([]);
  const [suggestionState, setSuggestionState] = useState<'idle' | 'loading' | 'results' | 'empty' | 'error'>('idle');
  const [activeSuggestionIndex, setActiveSuggestionIndex] = useState(-1);

  const containerRef = useRef<HTMLDivElement>(null);
  const searchBarRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const suggestionListRef = useRef<HTMLDivElement>(null);
  const suggestionRequestRef = useRef(0);
  const lastCommittedFiltersRef = useRef(filters);
  const touchStartYRef = useRef<number | null>(null);

  const summary = formatCompactSearchContext(filters);
  const hasFilters = hasActiveSearchFilters(filters);

  // Sync draft text if external committed filters change
  useEffect(() => {
    if (lastCommittedFiltersRef.current !== filters) {
      lastCommittedFiltersRef.current = filters;
      setSearchText(filters.q || summary || '');
      setIsEditing(false);
      setShowSuggestions(false);
    }
  }, [filters, summary]);

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
        setIsEditing(false);
        setShowSuggestions(false);
        setSearchText(filters.q || summary || '');
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isEditing, showSuggestions, filters.q, summary]);

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
        setSearchText(filters.q || summary || '');
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
      setSearchText(filters.q || summary || '');
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
  }, [isEditing, filters.q, summary]);

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
    flushSync(() => {
      setIsEditing(true);
      setSearchText(filters.q || summary || '');
    });
    searchInputRef.current?.focus();
    if ((filters.q || summary || '').trim().length >= 2) {
      setShowSuggestions(true);
    }
  };

  const exitEditMode = () => {
    setIsEditing(false);
    setShowSuggestions(false);
    setSearchText(filters.q || summary || '');
    searchInputRef.current?.blur();
  };

  const handleCitySelect = (newCity: string) => {
    setIsLocationOpen(false);
    setIsEditing(false);
    setShowSuggestions(false);
    setSearchText('');
    lastCommittedFiltersRef.current = { city: newCity, rentalOnly: true };
    onManualCityChange(newCity);
  };

  const handleClearAll = () => {
    setIsEditing(false);
    setShowSuggestions(false);
    setSuggestions([]);
    setSearchText('');
    lastCommittedFiltersRef.current = { city, rentalOnly: true };
    onClearAll();
  };

  const chooseSuggestion = (item: RentalSuggestion) => {
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
    lastCommittedFiltersRef.current = nextFilters;
    setSearchText(item.label);
    onSearch(nextFilters.city, nextFilters.sector, nextFilters);
  };

  const applySearch = () => {
    setIsEditing(false);
    setShowSuggestions(false);
    setSuggestions([]);
    searchInputRef.current?.blur();
    const query = searchText.trim();
    if (!query) {
      handleClearAll();
      return;
    }
    const nextFilters = buildRentalSearchFilters(city, query, null);
    lastCommittedFiltersRef.current = nextFilters;
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

  return (
    <>
      {/* FLOATING SEARCH WRAPPER — Outer relative container determining exact matched width */}
      <div
        ref={containerRef}
        id="compact-discovery-context"
        className="relative mx-auto w-full max-w-[860px]"
      >
        {/* 1. FLOATING SEARCH BAR (Single compact row on both desktop & mobile) */}
        <div
          ref={searchBarRef}
          id="compact-search-bar"
          className={`w-full rounded-2xl border bg-slate-950/80 backdrop-blur-xl backdrop-saturate-150 p-1.5 shadow-[0_12px_40px_rgb(0,0,0,0.3)] ring-1 ring-white/10 text-white transition-all sm:p-2 ${
            isEditing ? 'border-emerald-500/50 ring-2 ring-emerald-500/20' : 'border-white/15'
          }`}
        >
          <div className="flex h-10 w-full min-w-0 items-center gap-1.5 sm:gap-2">
            {/* City Selector */}
            <button
              type="button"
              id="compact-city-selector"
              onClick={() => {
                setIsEditing(false);
                setShowSuggestions(false);
                setIsLocationOpen(true);
              }}
              aria-haspopup="dialog"
              aria-label={`Current city: ${city || 'Choose city'}. Click to change.`}
              className="group inline-flex h-9 sm:h-10 shrink-0 items-center gap-1.5 sm:gap-2 rounded-xl px-2 sm:px-3 text-left transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
            >
              <MapPin className="h-4 w-4 shrink-0 text-emerald-400 transition-colors group-hover:text-emerald-300" aria-hidden="true" />
              <span className="max-w-[80px] sm:max-w-[140px] truncate text-xs font-bold text-white sm:text-sm tracking-tight">
                {city || 'Choose city'}
              </span>
              <ChevronDown className="h-3.5 w-3.5 shrink-0 text-slate-400 transition-transform group-hover:translate-y-0.5 group-hover:text-white" aria-hidden="true" />
            </button>

            {/* Faint Divider */}
            <div className="h-5 w-px shrink-0 bg-white/15" aria-hidden="true" />

            {/* CONDITIONAL CONTROLS: CONTEXT MODE vs EDIT MODE */}
            {!isEditing ? (
              /* CONTEXT MODE (Committed filter summary or clean placeholder) */
              <div className="flex h-9 sm:h-10 flex-1 min-w-0 items-center gap-1.5 sm:gap-2 px-1 sm:px-1.5">
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
              <div className="flex h-9 sm:h-10 flex-1 min-w-0 items-center gap-1.5 sm:gap-2 px-1 sm:px-1.5">
                <Search className="h-4 w-4 shrink-0 text-emerald-400" aria-hidden="true" />
                <input
                  ref={searchInputRef}
                  id="compact-search-input"
                  type="text"
                  role="combobox"
                  aria-autocomplete="list"
                  aria-expanded={showSuggestions && suggestions.length > 0}
                  aria-controls="compact-search-suggestions"
                  aria-activedescendant={activeSuggestionIndex >= 0 ? `compact-opt-${activeSuggestionIndex}` : undefined}
                  value={searchText}
                  onChange={(e) => {
                    setSearchText(e.target.value);
                    setShowSuggestions(true);
                  }}
                  onKeyDown={(e) => {
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
                  placeholder={summary || "Search locality or 2 BHK"}
                  aria-label="Search homes"
                  className="min-w-0 flex-1 bg-transparent text-sm font-medium text-white outline-none placeholder:text-slate-400"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck="false"
                />

                {/* Clear draft text button (×) - ONLY when searchText has text */}
                {searchText ? (
                  <button
                    type="button"
                    id="compact-clear-draft"
                    aria-label="Clear search text"
                    onClick={() => {
                      setSearchText('');
                      searchInputRef.current?.focus();
                      setShowSuggestions(false);
                    }}
                    className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-slate-400 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                  >
                    <X className="h-3.5 w-3.5" aria-hidden="true" />
                  </button>
                ) : null}

                {/* Submit arrow icon action (→) */}
                <button
                  type="button"
                  id="compact-search-submit"
                  aria-label="Submit search"
                  onClick={applySearch}
                  className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-emerald-600 text-white shadow-sm transition-all hover:bg-emerald-500 active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                >
                  <ArrowRight className="h-4 w-4" aria-hidden="true" />
                </button>
              </div>
            )}
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
            className="absolute left-0 right-0 top-[calc(100%+8px)] z-50 w-full max-h-[260px] sm:max-h-[300px] overflow-y-auto overscroll-contain rounded-2xl border border-slate-700/80 bg-slate-900/95 backdrop-blur-xl p-1.5 text-white shadow-[0_16px_50px_rgb(0,0,0,0.5)] ring-1 ring-white/10 divide-y divide-slate-800/60"
          >
            {suggestionState === 'loading' && (
              <div className="flex items-center gap-2.5 px-3 py-3 text-xs text-slate-400">
                <div className="h-3 w-3 animate-spin rounded-full border-2 border-emerald-500 border-t-transparent" />
                <span>Finding places…</span>
              </div>
            )}
            {suggestionState === 'empty' && (
              <p role="status" className="px-3 py-3 text-xs text-slate-400">No matching homes found.</p>
            )}
            {suggestionState === 'error' && (
              <p role="status" className="px-3 py-3 text-xs text-slate-400">Suggestions temporarily unavailable.</p>
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
                  onClick={() => chooseSuggestion(item)}
                  className={`flex min-h-[52px] w-full min-w-0 items-center justify-between gap-2.5 rounded-xl px-3 py-2 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${
                    index === activeSuggestionIndex
                      ? 'bg-emerald-950/80 text-white ring-1 ring-emerald-500/30'
                      : row.isAction
                      ? 'bg-slate-800/50 hover:bg-slate-800 text-white'
                      : 'hover:bg-slate-800/70 text-white'
                  }`}
                >
                  <div className="flex min-w-0 items-center gap-2.5">
                    {row.icon}
                    <div className="min-w-0">
                      <span className="block truncate text-xs sm:text-sm font-semibold text-white leading-snug">
                        {row.primary}
                      </span>
                      {row.secondary && (
                        <span className="block truncate text-[11px] text-slate-400 font-normal leading-tight mt-0.5">
                          {row.secondary}
                        </span>
                      )}
                    </div>
                  </div>

                  {row.badge && (
                    <span className="shrink-0 pl-2 text-[11px] font-normal text-slate-400">
                      {row.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* Shared City Dialog */}
      <DiscoveryLocationDialog
        open={isLocationOpen}
        step="city"
        onStepChange={() => {}}
        city={city}
        locality=""
        cityOnly
        onCityChange={handleCitySelect}
        onCitySelected={() => setIsLocationOpen(false)}
        onLocalityChange={() => {}}
        onApply={() => {}}
        onClose={() => setIsLocationOpen(false)}
      />
    </>
  );
};
