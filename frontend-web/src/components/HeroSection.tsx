import React, { useEffect, useRef, useState } from 'react';
import {
  AnimatePresence,
  motion,
  useReducedMotion,
  useScroll,
  useTransform,
} from 'framer-motion';
import { ArrowRight, ChevronDown, MapPin, Search, X } from 'lucide-react';
import { DiscoveryLocationDialog } from './DiscoveryLocationDialog';
import { propertyService } from '../services/propertyService';
import { buildRentalSearchFilters, normalizeSearchText, resetFiltersForManualCityChange, resetFiltersForSearchClear, RentalSearchFilters, RentalSuggestion, RentalPropertyType, RentalFurnishing, shouldSurfaceSuggestionFailure, suggestionFailureDiagnostic, suggestionFromStructuredFilters } from '../utils/rentalSearch';

interface HeroSectionProps {
  onSearch: (city?: string, sector?: string, filters?: Pick<RentalSearchFilters, 'q' | 'bhk' | 'propertyType' | 'furnishing' | 'minRent' | 'maxRent' | 'rentalOnly'>) => void;
  onOpenPostProperty: () => void;
  selectedCity?: string;
  selectedSector?: string;
  selectedQuery?: string;
  selectedBhk?: string;
  selectedPropertyType?: RentalPropertyType;
  selectedFurnishing?: RentalFurnishing;
  selectedMinRent?: number;
  selectedMaxRent?: number;
  refineRequest?: number;
}

const HERO_BANNERS = [
  { id: 'skyline', title: 'Indore skyline', image: '/assets/panoramic_skyline.jpg' },
  { id: 'luxury', title: 'Residential community', image: '/assets/hero_luxury.jpg' },
  { id: 'spatial', title: 'Property map', image: '/assets/spatial_gis.jpg' }
];

export const HeroSection: React.FC<HeroSectionProps> = ({ onSearch, onOpenPostProperty, selectedCity, selectedSector, selectedQuery, selectedBhk, selectedPropertyType, selectedFurnishing, selectedMinRent, selectedMaxRent, refineRequest = 0 }) => {
  const [draftCity, setDraftCity] = useState(selectedCity || '');
  const [searchText, setSearchText] = useState(selectedQuery || suggestionFromStructuredFilters(selectedCity, selectedSector, selectedBhk, selectedPropertyType, selectedFurnishing, selectedMinRent, selectedMaxRent)?.label || '');
  const [selection, setSelection] = useState<RentalSuggestion | null>(() => selectedQuery ? null : suggestionFromStructuredFilters(selectedCity, selectedSector, selectedBhk, selectedPropertyType, selectedFurnishing, selectedMinRent, selectedMaxRent));
  const [suggestions, setSuggestions] = useState<RentalSuggestion[]>([]);
  const [suggestionState, setSuggestionState] = useState<'idle' | 'loading' | 'results' | 'empty' | 'error'>('idle');
  const [suggestionsOpen, setSuggestionsOpen] = useState(false);
  const [activeSuggestionIndex, setActiveSuggestionIndex] = useState(-1);
  const [isEditing, setIsEditing] = useState(false);
  const [activeBannerIdx, setActiveBannerIdx] = useState(0);
  const [locationStep, setLocationStep] = useState<'city' | 'locality'>('city');
  const [isLocationOpen, setIsLocationOpen] = useState(false);
  const heroRef = useRef<HTMLElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const previousRefineRequest = useRef(refineRequest);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const suggestionListRef = useRef<HTMLDivElement>(null);
  const suggestionRequestRef = useRef(0);
  const prefersReducedMotion = useReducedMotion();
  const { scrollYProgress } = useScroll({ target: heroRef, offset: ['start start', 'end start'] });
  const backgroundY = useTransform(scrollYProgress, [0, 1], [0, 48]);
  const copyY = useTransform(scrollYProgress, [0, 1], [0, -18]);
  const copyOpacity = useTransform(scrollYProgress, [0, 1], [1, 0.84]);

  useEffect(() => {
    setDraftCity(selectedCity || '');
    const structured = selectedQuery ? null : suggestionFromStructuredFilters(selectedCity, selectedSector, selectedBhk, selectedPropertyType, selectedFurnishing, selectedMinRent, selectedMaxRent);
    setSearchText(selectedQuery || structured?.label || '');
    setSelection(structured);
    setIsEditing(false);
    setSuggestionsOpen(false);
  }, [selectedCity, selectedSector, selectedQuery, selectedBhk, selectedPropertyType, selectedFurnishing, selectedMinRent, selectedMaxRent]);

  useEffect(() => {
    if (previousRefineRequest.current === refineRequest) return;
    previousRefineRequest.current = refineRequest;
    window.requestAnimationFrame(() => searchInputRef.current?.focus());
  }, [refineRequest]);

  useEffect(() => {
    const query = normalizeSearchText(searchText);
    if (!isEditing || selection || query.length < 2) {
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
        const items = await propertyService.fetchRentalSuggestions(query, draftCity || undefined, controller.signal);
        if (requestId !== suggestionRequestRef.current || controller.signal.aborted) return;
        setSuggestions(items);
        setActiveSuggestionIndex(-1);
        setSuggestionState(items.length ? 'results' : 'empty');
        setSuggestionsOpen(true);
      } catch (error) {
        if (!shouldSurfaceSuggestionFailure(error, controller.signal.aborted, requestId === suggestionRequestRef.current)) return;
        if (import.meta.env.DEV) console.warn('Rental suggestions request failed', suggestionFailureDiagnostic(error, query.length));
        setSuggestions([]);
        setSuggestionState('error');
        setSuggestionsOpen(true);
      }
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [searchText, draftCity, isEditing, selection]);

  useEffect(() => {
    if (activeSuggestionIndex < 0) return;
    suggestionListRef.current?.querySelector<HTMLElement>(`#hero-search-option-${activeSuggestionIndex}`)
      ?.scrollIntoView({ block: 'nearest' });
  }, [activeSuggestionIndex]);

  useEffect(() => {
    if (prefersReducedMotion || isEditing || suggestionsOpen || isLocationOpen) return undefined;
    const timer = window.setInterval(() => {
      setActiveBannerIdx((previous) => (previous + 1) % HERO_BANNERS.length);
    }, 7000);
    return () => window.clearInterval(timer);
  }, [prefersReducedMotion, isEditing, suggestionsOpen, isLocationOpen]);

  const openLocation = (step: 'city' | 'locality', trigger: HTMLElement) => {
    returnFocusRef.current = trigger;
    setLocationStep(step);
    setIsLocationOpen(true);
  };

  const closeLocation = (discard: boolean) => {
    if (discard) setDraftCity(selectedCity || '');
    setIsLocationOpen(false);
    if (discard) {
      window.requestAnimationFrame(() => {
        const target = returnFocusRef.current?.isConnected ? returnFocusRef.current : document.getElementById('hero-search-btn');
        target?.focus({ preventScroll: true });
      });
    }
  };

  const applySearch = () => {
    setSuggestionsOpen(false);
    setIsEditing(false);
    const filters = buildRentalSearchFilters(draftCity, searchText, selection);
    if (filters.city && filters.city !== draftCity) {
      setDraftCity(filters.city);
    }
    onSearch(filters.city, filters.sector, filters);
  };

  const handleManualCityChange = (newCity: string) => {
    setDraftCity(newCity);
    setSearchText('');
    setSelection(null);
    setIsEditing(false);
    setSuggestionsOpen(false);
    setIsLocationOpen(false);
    const cleanFilters = resetFiltersForManualCityChange(newCity);
    onSearch(cleanFilters.city, undefined, cleanFilters);
  };

  const handleClearSearch = () => {
    setSearchText('');
    setSelection(null);
    setSuggestions([]);
    setSuggestionsOpen(false);
    setActiveSuggestionIndex(-1);
    setIsEditing(true);
    searchInputRef.current?.focus({ preventScroll: true });

    const hasCommittedSearch = Boolean(
      selectedQuery ||
      selectedSector ||
      selectedBhk ||
      selectedPropertyType ||
      selectedFurnishing ||
      selectedMinRent ||
      selectedMaxRent
    );

    if (hasCommittedSearch) {
      const currentCity = selectedCity || draftCity || 'Indore';
      const cleanFilters = resetFiltersForSearchClear(currentCity);
      onSearch(cleanFilters.city, undefined, cleanFilters);
    }
  };

  const chooseSuggestion = (item: RentalSuggestion) => {
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
      // Fire-and-forget telemetry must never block navigation or search
    }
    setSelection(item);
    setSearchText(item.label);
    setDraftCity(item.city);
    setIsEditing(false);
    setSuggestionsOpen(false);
    setActiveSuggestionIndex(-1);
    const filters = buildRentalSearchFilters(item.city, item.label, item);
    onSearch(filters.city, filters.sector, filters);
  };

  const currentBanner = HERO_BANNERS[activeBannerIdx];
  const displayCity = draftCity.trim() || null;

  return (
    <div className="relative bg-slate-950 font-['Inter',sans-serif]">
      <section ref={heroRef} className="relative flex min-h-[clamp(660px,85svh,820px)] w-full flex-col px-4 pb-8 pt-24 sm:px-6 sm:pb-10 sm:pt-28 lg:min-h-[clamp(680px,82svh,860px)] lg:px-8 lg:pb-12 lg:pt-32">
        {/* Animated background */}
        <div className="pointer-events-none absolute inset-0 z-0 overflow-hidden">
          <AnimatePresence mode="wait" initial={!prefersReducedMotion}>
            <motion.div
              key={currentBanner.id}
              initial={prefersReducedMotion ? false : { opacity: 0, scale: 1.04 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={prefersReducedMotion ? undefined : { opacity: 0, scale: 0.98 }}
              transition={prefersReducedMotion ? { duration: 0 } : { duration: 0.7, ease: [0.16, 1, 0.3, 1] }}
              style={prefersReducedMotion ? undefined : { y: backgroundY }}
              className="absolute -inset-y-12 inset-x-0 overflow-hidden"
            >
              <img src={currentBanner.image} alt="" className="h-full w-full object-cover" />
              {/* Top gradient for guaranteed contrast behind overlay navbar */}
              <div className="absolute inset-0 bg-gradient-to-b from-slate-950/70 via-slate-950/20 to-transparent pointer-events-none" />
              {/* Bottom gradient */}
              <div className="absolute inset-0 bg-gradient-to-t from-slate-950/85 via-slate-950/50 to-slate-950/15 pointer-events-none" />
            </motion.div>
          </AnimatePresence>
        </div>

        {/* Keep the image open above the compact search and place the content near the lower edge. */}
        <div className="relative z-10 mx-auto flex w-full max-w-7xl flex-1 flex-col justify-end py-3 sm:py-4 lg:py-6">
          {/* Hero copy */}
          <motion.div style={prefersReducedMotion ? undefined : { y: copyY, opacity: copyOpacity }} className="text-center sm:text-left">
            <motion.h1
              initial={prefersReducedMotion ? false : { opacity: 0, y: 14 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: prefersReducedMotion ? 0 : 0.45, delay: 0.08 }}
              className="max-w-3xl font-['Outfit',sans-serif] text-3xl font-black leading-[1.14] tracking-tight text-white break-words sm:text-5xl lg:text-6xl"
            >
              <span>Find your next home</span>
              {displayCity && (
                <>
                  {' '}
                  <span className="inline-block max-w-full break-words">
                    in <span className="text-emerald-300">{displayCity}</span>
                  </span>
                </>
              )}
            </motion.h1>
            <motion.p
              initial={prefersReducedMotion ? false : { opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: prefersReducedMotion ? 0 : 0.4, delay: 0.18 }}
              className="mx-auto mt-3 max-w-xl text-sm leading-relaxed text-slate-300 sm:mx-0 sm:mt-4 sm:text-base"
            >
              Explore rental homes with clear property details and request a visit online.
            </motion.p>
          </motion.div>

          {/* One composed search, shared with the results refinement action. */}
          <div className="mt-5 w-full max-w-[54rem] sm:mt-6">
            <motion.div
              initial={prefersReducedMotion ? false : { opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={
                prefersReducedMotion
                  ? { duration: 0 }
                  : { type: 'spring', stiffness: 280, damping: 24, delay: 0.24 }
              }
              id="hero-search-surface"
              className="relative z-30 grid min-w-0 grid-cols-1 gap-1 rounded-2xl border border-white/30 bg-white p-1.5 shadow-[0_20px_50px_-24px_rgba(2,6,23,0.7)] transition-shadow duration-300 focus-within:shadow-[0_22px_54px_-22px_rgba(2,6,23,0.8)] sm:p-2 md:grid-cols-[minmax(0,0.75fr)_minmax(0,1.65fr)_auto] md:items-stretch md:gap-0 motion-reduce:transition-none"
            >
              <button
                type="button"
                id="hero-search-city"
                onClick={(event) => openLocation('city', event.currentTarget)}
                aria-haspopup="dialog"
                aria-expanded={isLocationOpen && locationStep === 'city'}
                className="group flex min-h-11 min-w-0 items-center gap-2.5 rounded-xl px-3 text-left transition-colors duration-150 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 md:min-h-12 md:rounded-r-none md:border-r md:border-slate-200 motion-reduce:transition-none"
              >
                <MapPin className="h-4 w-4 shrink-0 text-emerald-700" aria-hidden="true" />
                <span className="min-w-0 flex-1">
                  <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-500">City</span>
                  <span className="block truncate text-base font-bold text-slate-900">{draftCity || 'Choose city'}</span>
                </span>
                <ChevronDown className="h-4 w-4 shrink-0 text-slate-400 transition-transform duration-150 group-hover:translate-y-0.5 motion-reduce:group-hover:translate-y-0 motion-reduce:transition-none" aria-hidden="true" />
              </button>
              <div className="relative z-30 flex min-h-11 min-w-0 items-center gap-2 rounded-xl px-3 focus-within:ring-2 focus-within:ring-emerald-600 md:min-h-12 md:rounded-none">
                <Search className="h-4 w-4 shrink-0 text-emerald-700" aria-hidden="true" />
                <label htmlFor="hero-smart-search" className="sr-only">Search rental homes by locality or BHK</label>
                <input
                  ref={searchInputRef}
                  id="hero-smart-search"
                  type="text"
                  inputMode="search"
                  enterKeyHint="search"
                  role="combobox"
                  aria-autocomplete="list"
                  aria-expanded={suggestionsOpen && suggestionState !== 'idle'}
                  aria-controls="hero-search-suggestions"
                  aria-activedescendant={suggestionsOpen && activeSuggestionIndex >= 0 ? `hero-search-option-${activeSuggestionIndex}` : undefined}
                  autoComplete="off"
                  value={searchText}
                  onFocus={() => { setIsEditing(true); if (normalizeSearchText(searchText).length >= 2 && !selection) setSuggestionsOpen(true); }}
                  onBlur={() => { setIsEditing(false); setSuggestionsOpen(false); }}
                  onChange={(event) => { setSearchText(event.target.value); setSelection(null); setIsEditing(true); setSuggestionsOpen(true); }}
                  onKeyDown={(event) => {
                    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                      if (suggestions.length) {
                        event.preventDefault();
                        setSuggestionsOpen(true);
                        setActiveSuggestionIndex((index) => event.key === 'ArrowDown'
                          ? (index + 1) % suggestions.length
                          : (index <= 0 ? suggestions.length - 1 : index - 1));
                      }
                    } else if (event.key === 'Escape') {
                      setSuggestionsOpen(false);
                      setActiveSuggestionIndex(-1);
                    } else if (event.key === 'Enter') {
                      event.preventDefault();
                      if (suggestionsOpen && activeSuggestionIndex >= 0 && suggestions[activeSuggestionIndex]) {
                        chooseSuggestion(suggestions[activeSuggestionIndex]);
                      } else {
                        applySearch();
                      }
                    }
                  }}
                  placeholder="Search locality or 2 BHK"
                  className="min-w-0 flex-1 bg-transparent py-2 text-base font-medium text-slate-900 outline-none placeholder:text-slate-500"
                />
                {searchText && (
                  <button
                    type="button"
                    aria-label="Clear search"
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={handleClearSearch}
                    className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-slate-500 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600"
                  >
                    <X className="h-4 w-4" aria-hidden="true" />
                  </button>
                )}
                  <div ref={suggestionListRef} id="hero-search-suggestions" role="listbox" aria-label="Rental search suggestions" className={suggestionsOpen && suggestionState !== 'idle' ? 'absolute inset-x-0 top-[calc(100%+0.5rem)] z-50 max-h-[min(20rem,45dvh)] overflow-y-auto overscroll-contain rounded-2xl border border-slate-200 bg-white p-1.5 text-slate-900 shadow-2xl' : 'hidden'}>
                    {suggestionState === 'loading' && <p role="status" className="px-3 py-3 text-sm text-slate-600">Finding places…</p>}
                    {suggestionState === 'empty' && <p role="status" className="px-3 py-3 text-sm text-slate-600">No matching homes found.</p>}
                    {suggestionState === 'error' && <p role="status" className="px-3 py-3 text-sm text-slate-600">Suggestions are temporarily unavailable. You can still search.</p>}
                    {suggestionState === 'results' && suggestions.map((item, index) => (
                      <button
                        key={`${item.type}-${item.city}-${item.locality || ''}-${item.bhk || ''}-${index}`}
                        id={`hero-search-option-${index}`}
                        type="button"
                        role="option"
                        aria-selected={index === activeSuggestionIndex}
                        tabIndex={-1}
                        onMouseDown={(event) => event.preventDefault()}
                        onClick={() => chooseSuggestion(item)}
                        className={`flex min-h-12 w-full min-w-0 items-center gap-3 rounded-xl px-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 ${index === activeSuggestionIndex ? 'bg-emerald-50 text-emerald-900' : 'hover:bg-slate-50'}`}
                      >
                        <MapPin className="h-4 w-4 shrink-0 text-emerald-700" aria-hidden="true" />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm font-semibold">{item.label}</span>
                          <span className="block truncate text-xs text-slate-500">
                            {item.type === 'ENTITY_MATCH'
                              ? 'Explore homes in this area'
                              : item.type === 'UNSUPPORTED_CITY'
                              ? 'City currently unavailable'
                              : item.type === 'QUERY_INTENT'
                              ? 'Search this requirement'
                              : item.type === 'SEARCH_ANYWAY'
                              ? 'Search all listings'
                              : item.type === 'CITY'
                              ? 'City'
                              : item.type === 'SEARCH_QUERY'
                              ? 'BHK and locality'
                              : 'Locality'}
                            {item.type !== 'QUERY_INTENT' && item.type !== 'SEARCH_ANYWAY' && item.type !== 'UNSUPPORTED_CITY' && item.type !== 'ENTITY_MATCH' && item.resultCount !== null
                              ? ` · ${item.resultCount} ${item.resultCount === 1 ? 'home' : 'homes'}`
                              : ''}
                          </span>
                        </span>
                      </button>
                    ))}
                  </div>
              </div>
              <button
                type="button"
                id="hero-search-btn"
                onClick={applySearch}
                className="group inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-5 text-sm font-bold text-white transition-colors duration-150 hover:bg-emerald-700 active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 focus-visible:ring-offset-2 md:ml-1 md:min-h-12 motion-reduce:active:scale-100 motion-reduce:transition-none"
              >
                Show homes
                <ArrowRight className="h-4 w-4 transition-transform duration-150 group-hover:translate-x-0.5 motion-reduce:group-hover:translate-x-0 motion-reduce:transition-none" aria-hidden="true" />
              </button>
            </motion.div>

            <motion.div
              initial={prefersReducedMotion ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: prefersReducedMotion ? 0 : 0.45, delay: prefersReducedMotion ? 0 : 0.32 }}
              className="mt-2 flex min-h-11 flex-wrap items-center justify-center gap-x-2 gap-y-0 text-center sm:justify-start sm:text-left lg:hidden"
            >
              <span className="text-xs font-medium text-white/70">Own a property?</span>
              <button
                type="button"
                onClick={onOpenPostProperty}
                className="group inline-flex min-h-11 items-center gap-1.5 text-xs font-semibold text-emerald-200 underline decoration-emerald-300/40 underline-offset-4 transition-colors hover:text-white hover:decoration-white focus-visible:rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-300"
              >
                Post your property
                <ArrowRight className="h-3.5 w-3.5 transition-transform duration-200 group-hover:translate-x-0.5 motion-reduce:group-hover:translate-x-0 motion-reduce:transition-none" aria-hidden="true" />
              </button>
            </motion.div>

            {/* Banner navigation dots */}
            <div
              className="mt-1 flex justify-center gap-0 sm:justify-start"
              role="group"
              aria-label="Banner navigation"
            >
              {HERO_BANNERS.map((banner, index) => (
                <button
                  key={banner.id}
                  type="button"
                  onClick={() => setActiveBannerIdx(index)}
                  className="group flex h-11 w-11 items-center justify-center rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-300"
                  aria-label={`Show ${banner.title} image`}
                  aria-current={index === activeBannerIdx ? 'true' : undefined}
                >
                  <span className={`h-2 rounded-full transition-[width,background-color] duration-300 motion-reduce:transition-none ${
                    index === activeBannerIdx ? 'w-7 bg-emerald-400' : 'w-2 bg-white/60 group-hover:bg-white'
                  }`} />
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>
      <DiscoveryLocationDialog
        open={isLocationOpen}
        step={locationStep}
        onStepChange={setLocationStep}
        city={draftCity}
        locality=""
        cityOnly
        onCityChange={handleManualCityChange}
        onCitySelected={() => setIsLocationOpen(false)}
        onLocalityChange={() => {}}
        onApply={applySearch}
        onClose={() => closeLocation(true)}
      />
    </div>
  );
};
