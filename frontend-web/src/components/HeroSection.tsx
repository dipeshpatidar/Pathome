import React, { useEffect, useRef, useState } from 'react';
import {
  AnimatePresence,
  motion,
  useReducedMotion,
} from 'framer-motion';
import { ArrowRight, ChevronDown, MapPin, Search, X } from 'lucide-react';
import { DiscoveryLocationDialog } from './DiscoveryLocationDialog';
import { propertyService } from '../services/propertyService';
import { buildRentalSearchFilters, normalizeSearchText, RentalSearchFilters, RentalSuggestion, RentalPropertyType, RentalFurnishing, shouldSurfaceSuggestionFailure, suggestionFailureDiagnostic, suggestionFromStructuredFilters } from '../utils/rentalSearch';

interface HeroSectionProps {
  onSearch: (city?: string, sector?: string, filters?: Pick<RentalSearchFilters, 'q' | 'bhk' | 'propertyType' | 'furnishing' | 'minRent' | 'maxRent' | 'rentalOnly'>) => void;
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

export const HeroSection: React.FC<HeroSectionProps> = ({ onSearch, selectedCity, selectedSector, selectedQuery, selectedBhk, selectedPropertyType, selectedFurnishing, selectedMinRent, selectedMaxRent, refineRequest = 0 }) => {
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
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const previousRefineRequest = useRef(refineRequest);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const suggestionListRef = useRef<HTMLDivElement>(null);
  const suggestionRequestRef = useRef(0);
  const prefersReducedMotion = useReducedMotion();

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
    if (prefersReducedMotion) return undefined;
    const timer = window.setInterval(() => {
      setActiveBannerIdx((previous) => (previous + 1) % HERO_BANNERS.length);
    }, 7000);
    return () => window.clearInterval(timer);
  }, [prefersReducedMotion]);

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
    const filters = buildRentalSearchFilters(draftCity, searchText, selection);
    onSearch(filters.city, filters.sector, filters);
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
    searchInputRef.current?.focus();
  };

  const currentBanner = HERO_BANNERS[activeBannerIdx];
  const displayCity = draftCity.trim() || null;

  return (
    <div className="relative bg-slate-950 font-['Inter',sans-serif]">
      <section className="relative flex min-h-[540px] w-full flex-col px-4 pb-12 pt-24 sm:min-h-[580px] sm:px-6 sm:pt-28 sm:pb-14 lg:min-h-[620px] lg:px-8 lg:pt-32 lg:pb-16">
        {/* Animated background */}
        <AnimatePresence mode="wait" initial={!prefersReducedMotion}>
          <motion.div
            key={currentBanner.id}
            initial={prefersReducedMotion ? false : { opacity: 0, scale: 1.04 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={prefersReducedMotion ? undefined : { opacity: 0, scale: 0.98 }}
            transition={prefersReducedMotion ? { duration: 0 } : { duration: 0.7, ease: [0.16, 1, 0.3, 1] }}
            className="pointer-events-none absolute inset-0 z-0 overflow-hidden"
          >
            <motion.img
              src={currentBanner.image}
              alt=""
              animate={prefersReducedMotion ? undefined : { scale: [1, 1.035, 1], x: [0, -8, 0] }}
              transition={prefersReducedMotion ? undefined : { duration: 16, repeat: Infinity, ease: 'easeInOut' }}
              className="h-full w-full object-cover"
            />
            {/* Top gradient for guaranteed contrast behind overlay navbar */}
            <div className="absolute inset-0 bg-gradient-to-b from-slate-950/85 via-slate-950/35 to-transparent pointer-events-none" />
            {/* Bottom gradient */}
            <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/80 to-slate-950/40 pointer-events-none" />
          </motion.div>
        </AnimatePresence>

        {/* Content: Stable vertical rhythm connecting Headline -> Subtitle -> Search -> Dots */}
        <div className="relative z-10 mx-auto flex w-full max-w-7xl flex-1 flex-col justify-center py-4 sm:py-6 lg:py-8">
          {/* Hero copy */}
          <div className="text-center sm:text-left">
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
              Verified rental homes with transparent pricing — request a visit in minutes.
            </motion.p>
          </div>

          {/* One composed search, shared with the results refinement action. */}
          <div className="mt-6 sm:mt-8 w-full max-w-[48rem]">
            <motion.div
              initial={prefersReducedMotion ? false : { opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={
                prefersReducedMotion
                  ? { duration: 0 }
                  : { type: 'spring', stiffness: 280, damping: 24, delay: 0.24 }
              }
              id="hero-search-surface"
              className="relative z-20 grid min-w-0 grid-cols-1 gap-1.5 rounded-2xl border border-white/20 bg-white p-1.5 shadow-xl shadow-slate-950/25 sm:grid-cols-2 sm:p-2 lg:grid-cols-[minmax(0,0.8fr)_minmax(0,2fr)_auto] lg:items-stretch"
            >
              <button
                type="button"
                id="hero-search-city"
                onClick={(event) => openLocation('city', event.currentTarget)}
                aria-haspopup="dialog"
                aria-expanded={isLocationOpen && locationStep === 'city'}
                className="group flex min-h-14 min-w-0 items-center gap-3 rounded-xl px-4 text-left transition-colors duration-150 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600"
              >
                <MapPin className="h-5 w-5 shrink-0 text-emerald-700" aria-hidden="true" />
                <span className="min-w-0 flex-1">
                  <span className="block text-[11px] font-semibold uppercase tracking-wide text-slate-500">City</span>
                  <span className="block truncate text-base font-bold text-slate-900">{draftCity || 'Choose city'}</span>
                </span>
                <ChevronDown className="h-4 w-4 shrink-0 text-slate-400 transition-transform duration-150 group-hover:translate-y-0.5" aria-hidden="true" />
              </button>
              <div className="relative flex min-h-14 min-w-0 items-center gap-2 rounded-xl px-4 focus-within:ring-2 focus-within:ring-emerald-600">
                <Search className="h-5 w-5 shrink-0 text-emerald-700" aria-hidden="true" />
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
                  className="min-w-0 flex-1 bg-transparent py-3 text-base font-medium text-slate-900 outline-none placeholder:text-slate-500"
                />
                {searchText && <button type="button" aria-label="Clear search" onMouseDown={(event) => event.preventDefault()} onClick={() => { setSearchText(''); setSelection(null); setSuggestions([]); setSuggestionsOpen(false); searchInputRef.current?.focus(); }} className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-slate-500 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600"><X className="h-4 w-4" aria-hidden="true" /></button>}
                  <div ref={suggestionListRef} id="hero-search-suggestions" role="listbox" aria-label="Rental search suggestions" className={suggestionsOpen && suggestionState !== 'idle' ? 'absolute inset-x-0 top-[calc(100%+0.5rem)] z-50 max-h-[min(20rem,45dvh)] overflow-y-auto overscroll-contain rounded-2xl border border-slate-200 bg-white p-1.5 text-slate-900 shadow-2xl' : 'hidden'}>
                    {suggestionState === 'loading' && <p role="status" className="px-3 py-3 text-sm text-slate-600">Finding places…</p>}
                    {suggestionState === 'empty' && <p role="status" className="px-3 py-3 text-sm text-slate-600">No matching homes found.</p>}
                    {suggestionState === 'error' && <p role="status" className="px-3 py-3 text-sm text-slate-600">Suggestions are temporarily unavailable. You can still search.</p>}
                    {suggestionState === 'results' && suggestions.map((item, index) => (
                      <button
                        key={`${item.type}-${item.city}-${item.locality || ''}-${item.bhk || ''}`}
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
                        <span className="min-w-0 flex-1"><span className="block truncate text-sm font-semibold">{item.label}</span><span className="block truncate text-xs text-slate-500">{item.type === 'CITY' ? 'City' : item.type === 'SEARCH_QUERY' ? 'BHK and locality' : 'Locality'}{item.resultCount !== null ? ` · ${item.resultCount} ${item.resultCount === 1 ? 'home' : 'homes'}` : ''}</span></span>
                      </button>
                    ))}
                  </div>
              </div>
              <button
                type="button"
                id="hero-search-btn"
                onClick={applySearch}
                className="inline-flex min-h-14 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-6 text-sm font-bold text-white transition-colors duration-150 hover:bg-emerald-700 active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 focus-visible:ring-offset-2 sm:col-span-2 lg:col-span-1"
              >
                Show homes
                <ArrowRight className="h-4 w-4" aria-hidden="true" />
              </button>
            </motion.div>

            {/* Banner navigation dots */}
            <div
              className="mt-4 sm:mt-5 flex justify-center gap-2 sm:justify-start"
              role="group"
              aria-label="Banner navigation"
            >
              {HERO_BANNERS.map((banner, index) => (
                <button
                  key={banner.id}
                  type="button"
                  onClick={() => setActiveBannerIdx(index)}
                  className={`h-2 rounded-full transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-300 ${
                    index === activeBannerIdx ? 'w-7 bg-emerald-400' : 'w-2 bg-white/35 hover:bg-white/60'
                  }`}
                  aria-label={`Show ${banner.title} image`}
                  aria-current={index === activeBannerIdx ? 'true' : undefined}
                />
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
        onCityChange={(city) => { setDraftCity(city); setSearchText(''); setSelection(null); }}
        onCitySelected={() => { setIsLocationOpen(false); window.requestAnimationFrame(() => searchInputRef.current?.focus()); }}
        onLocalityChange={() => {}}
        onApply={applySearch}
        onClose={() => closeLocation(true)}
      />
    </div>
  );
};
