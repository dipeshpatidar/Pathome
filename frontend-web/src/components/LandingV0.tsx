import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Activity, ArrowRight, ArrowUpLeft, CalendarCheck2, Camera, Check, ChevronDown, Clock3, CloudOff, CornerDownLeft, Heart, Home, ImageOff, ListChecks, LoaderCircle, MapPin, MapPinned, Menu, Play, RotateCcw, Route, Search, SearchX, SlidersHorizontal, WifiOff, X } from 'lucide-react';
import type { Property } from '../types';
import { propertyService } from '../services/propertyService';
import { buildCloudinaryUrl } from '../utils/mediaTransform';
import { formatExactPropertyTimestampIST, formatPropertyRelativeTime, parsePropertyTimestamp } from '../utils/propertyTimestamp';
import { resolvePropertyVideoUrl } from '../utils/propertyVideo';
import { landingLocality, landingPropertyTitle } from '../utils/landingPropertyData';
import { emptyLandingPart, landingAppliedCriteria, landingEffectiveFilters, removeLandingCriterion, type LandingState } from '../utils/landingSearchState';
import type { LessorDraftSummary } from '../services/lessorDraftService';
import { DISCOVERY_CITIES } from './discoveryLocations';
import { buildRentalSearchFilters, FURNISHING_LABELS, normalizeSearchText, PROPERTY_TYPE_LABELS, resetFiltersForManualCityChange, resetFiltersForSearchClear, shouldSurfaceSuggestionFailure, suggestionFailureDiagnostic, type RentalFurnishing, type RentalPropertyType, type RentalSearchFilters, type RentalSuggestion } from '../utils/rentalSearch';
import '../landingV0.css';

type SearchInput = Pick<RentalSearchFilters, 'q' | 'bhk' | 'propertyType' | 'furnishing' | 'minRent' | 'maxRent' | 'rentalOnly'>;
type LandingV0Props = {
  city: string;
  filters: RentalSearchFilters;
  landingState: LandingState;
  properties: Property[];
  loading: boolean;
  error: boolean;
  hasMore: boolean;
  loadingMore: boolean;
  loadMoreError: boolean;
  onSearch: (city?: string, locality?: string, filters?: SearchInput, state?: LandingState) => void;
  onRetry: () => void;
  onLoadMore: () => void;
  onOpenProperty: (property: Property) => void;
  onSaveFavorite: (property: Property) => void;
  onSignIn: () => void;
  onListProperty: () => void;
  authenticated?: boolean;
  savedPropertyIds?: ReadonlySet<number>;
  favoriteReady?: boolean;
  favoriteError?: boolean;
  onRetryFavorites?: () => void;
  favoritePendingIds?: ReadonlySet<number>;
  latestDraft?: LessorDraftSummary | null;
  draftCount?: number;
  onOpenDraft?: (draftId: string) => void;
  onViewAllDrafts?: () => void;
};

const rent = (value: number) => `₹${Math.max(0, value || 0).toLocaleString('en-IN')}`;
const currentYear = new Date().getFullYear();
const allTypes = Object.keys(PROPERTY_TYPE_LABELS) as RentalPropertyType[];
const allFurnishings = Object.keys(FURNISHING_LABELS) as RentalFurnishing[];
const budgets = [
  { label: 'Any budget', min: undefined, max: undefined },
  { label: 'Under ₹15k', min: undefined, max: 15000 },
  { label: '₹15k – 25k', min: 15000, max: 25000 },
  { label: '₹25k – 40k', min: 25000, max: 40000 },
  { label: '₹40k +', min: 40000, max: undefined }
];

function Brand({ tagline = false }: { tagline?: boolean }) {
  return <span className="lp-brand"><span className="lp-brand-mark" aria-hidden="true"><Home size={17} strokeWidth={1.8}/></span><span className="lp-brand-word">pathome<span className="lp-brand-dot">.</span></span>{tagline && <span className="lp-brand-tagline">Your Dreams, Our Efforts.</span>}</span>;
}

function LandingNav({ onSignIn, onListProperty }: Pick<LandingV0Props, 'onSignIn' | 'onListProperty'>) {
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const firstLink = useRef<HTMLAnchorElement>(null);
  useEffect(() => {
    const update = () => setScrolled(window.scrollY > 8);
    update(); window.addEventListener('scroll', update, { passive: true });
    return () => window.removeEventListener('scroll', update);
  }, []);
  useEffect(() => {
    if (!open) return;
    const y = window.scrollY;
    const body = document.body;
    const before = { overflow: body.style.overflow, position: body.style.position, top: body.style.top, width: body.style.width };
    body.style.overflow = 'hidden'; body.style.position = 'fixed'; body.style.top = `-${y}px`; body.style.width = '100%';
    firstLink.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
      if (event.key !== 'Tab') return;
      const panel = document.getElementById('lp-mobile-menu');
      const items = Array.from(panel?.querySelectorAll<HTMLElement>('a, button') || []);
      if (!items.length) return;
      const first = items[0], last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      Object.assign(body.style, before);
      window.scrollTo({ top: y, behavior: 'instant' });
      trigger.current?.focus({ preventScroll: true });
    };
  }, [open]);
  useEffect(() => {
    const resize = () => { if (window.innerWidth >= 1024) setOpen(false); };
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  return <>
    <header className={`lp-nav ${scrolled || open ? 'is-scrolled' : ''}`}>
      <div className="lp-container lp-nav-inner">
        <Link to="/" className="lp-nav-brand" aria-label="Pathome home"><Brand tagline/></Link>
        <nav className="lp-nav-links" aria-label="Primary"><a href="#homes">Explore homes</a><a href="#how-it-works">How it works</a><a href="#list-property">For owners</a><button type="button" onClick={onListProperty}>List your property</button></nav>
        <div className="lp-nav-actions"><button type="button" className="lp-btn lp-btn-secondary lp-nav-signin" onClick={onSignIn}>Sign in</button><button ref={trigger} type="button" className="lp-nav-menu-button" aria-label={open ? 'Close menu' : 'Open menu'} aria-expanded={open} aria-controls="lp-mobile-menu" onClick={() => setOpen(v => !v)}>{open ? <X size={20}/> : <Menu size={20}/>}</button></div>
      </div>
    </header>
    {open && <><button className="lp-mobile-menu-backdrop" type="button" aria-label="Close menu" onClick={() => setOpen(false)}/><div id="lp-mobile-menu" className="lp-mobile-menu"><nav className="lp-container" aria-label="Mobile"><a ref={firstLink} href="#homes" onClick={() => setOpen(false)}>Explore homes</a><a href="#how-it-works" onClick={() => setOpen(false)}>How it works</a><a href="#list-property" onClick={() => setOpen(false)}>For owners</a><button type="button" className="lp-mobile-menu-signin" onClick={() => { setOpen(false); onListProperty(); }}>List your property</button><button type="button" className="lp-mobile-menu-signin" onClick={() => { setOpen(false); onSignIn(); }}>Sign in</button><p className="lp-mobile-menu-tagline">Your Dreams, Our Efforts.</p></nav></div></>}
  </>;
}

function SearchInstrument({ city, landingState, loading, onSearch }: Pick<LandingV0Props, 'city' | 'landingState' | 'loading' | 'onSearch'>) {
  const [draftCity, setDraftCity] = useState(city);
  const [query, setQuery] = useState(landingState.searchLabel || landingState.search.q || '');
  const [selected, setSelected] = useState<RentalSuggestion | null>(null);
  const [homeType, setHomeType] = useState<RentalPropertyType | ''>(landingState.search.propertyType || '');
  const [filterType, setFilterType] = useState<RentalPropertyType | ''>(landingState.explicit.propertyType || '');
  const [draftFilters, setDraftFilters] = useState<{ bhk: string; minRent?: number; maxRent?: number; locality: string; furnishing: RentalFurnishing | '' }>({ bhk: landingState.explicit.bhk || '', minRent: landingState.explicit.minRent, maxRent: landingState.explicit.maxRent, locality: landingState.explicit.sector || '', furnishing: landingState.explicit.furnishing || '' });
  const [cityOpen, setCityOpen] = useState(false);
  const [cityFilter, setCityFilter] = useState('');
  const [cityIndex, setCityIndex] = useState(0);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [suggestionsOpen, setSuggestionsOpen] = useState(false);
  const [suggestions, setSuggestions] = useState<RentalSuggestion[]>([]);
  const [suggestionState, setSuggestionState] = useState<'idle' | 'loading' | 'ready' | 'empty' | 'unavailable'>('idle');
  const [active, setActive] = useState(-1);
  const searchRef = useRef<HTMLInputElement>(null);
  const cityRef = useRef<HTMLDivElement>(null);
  const queryRef = useRef<HTMLDivElement>(null);
  const filtersRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const requestId = useRef(0);
  const cityOptions = DISCOVERY_CITIES.filter(option => option.name.toLowerCase().includes(cityFilter.trim().toLowerCase()));
  const count = landingAppliedCriteria(landingState.explicit).length;
  useEffect(() => {
    setDraftCity(city);
    setQuery(landingState.searchLabel || landingState.search.q || ''); setSelected(null); setHomeType(landingState.search.propertyType || ''); setFilterType(landingState.explicit.propertyType || '');
    setDraftFilters({ bhk: landingState.explicit.bhk || '', minRent: landingState.explicit.minRent, maxRent: landingState.explicit.maxRent, locality: landingState.explicit.sector || '', furnishing: landingState.explicit.furnishing || '' });
  }, [city, landingState.searchLabel, landingState.search.q, landingState.search.propertyType, landingState.explicit.bhk, landingState.explicit.propertyType, landingState.explicit.furnishing, landingState.explicit.minRent, landingState.explicit.maxRent, landingState.explicit.sector]);
  useEffect(() => {
    const handle = (event: PointerEvent) => {
      const node = event.target as Node;
      if (!cityRef.current?.contains(node)) setCityOpen(false);
      if (!queryRef.current?.contains(node)) setSuggestionsOpen(false);
      if (!filtersRef.current?.contains(node)) setFiltersOpen(false);
    };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setCityOpen(false); setFiltersOpen(false); setSuggestionsOpen(false); } };
    document.addEventListener('pointerdown', handle); document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', handle); document.removeEventListener('keydown', escape); };
  }, []);
  useEffect(() => {
    const q = normalizeSearchText(query);
    if (!suggestionsOpen || selected || q.length < 2) { setSuggestions([]); setSuggestionState('idle'); return; }
    const id = ++requestId.current;
    const controller = new AbortController();
    setSuggestionState('loading');
    const timer = window.setTimeout(async () => {
      try {
        const items = await propertyService.fetchRentalSuggestions(q, draftCity || undefined, controller.signal);
        if (controller.signal.aborted || id !== requestId.current) return;
        setSuggestions(items); setActive(-1); setSuggestionState(items.length ? 'ready' : 'empty');
      } catch (error) {
        if (!shouldSurfaceSuggestionFailure(error, controller.signal.aborted, id === requestId.current)) return;
        if (import.meta.env.DEV) console.warn('Rental suggestions request failed', suggestionFailureDiagnostic(error, q.length));
        setSuggestions([]); setSuggestionState('unavailable');
      }
    }, 250);
    return () => { controller.abort(); window.clearTimeout(timer); };
  }, [query, draftCity, selected, suggestionsOpen]);
  useEffect(() => {
    if (!filtersOpen) return;
    headingRef.current?.focus();
    const mobile = window.matchMedia('(max-width: 719px)').matches;
    const prior = document.body.style.overflow;
    if (mobile) document.body.style.overflow = 'hidden';
    const trap = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      const panel = filtersRef.current?.querySelector('.lp-filters');
      const controls = Array.from(panel?.querySelectorAll<HTMLElement>('button, input:not([disabled])') || []);
      if (!controls.length) return;
      const first = controls[0], last = controls[controls.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === headingRef.current)) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus();
      }
    };
    document.addEventListener('keydown', trap);
    return () => { document.body.style.overflow = prior; document.removeEventListener('keydown', trap); };
  }, [filtersOpen]);
  const explicitFromDraft = (targetCity: string, next = draftFilters, type = filterType): RentalSearchFilters => ({
    city: targetCity, rentalOnly: true, bhk: next.bhk || undefined, sector: next.locality.trim() || undefined,
    furnishing: next.furnishing || undefined, propertyType: type || undefined, minRent: next.minRent, maxRent: next.maxRent
  });
  const commit = (state: LandingState, targetCity: string) => {
    const effective = landingEffectiveFilters(state, targetCity);
    setSuggestionsOpen(false); setFiltersOpen(false);
    onSearch(targetCity, effective.sector, effective, state);
  };
  const execute = (text = query, suggestion: RentalSuggestion | null = selected, targetCity = draftCity) => {
    const base = buildRentalSearchFilters(targetCity, text, suggestion);
    const effectiveCity = base.city || targetCity;
    const search = { ...base, propertyType: homeType || base.propertyType, city: effectiveCity, rentalOnly: true };
    commit({ search, explicit: { ...landingState.explicit, city: effectiveCity }, searchLabel: text.trim() }, effectiveCity);
  };
  const applyFilters = () => commit({ ...landingState, explicit: explicitFromDraft(draftCity) }, draftCity);
  const clearAllDiscovery = () => {
    setQuery(''); setSelected(null); setSuggestionsOpen(false); setActive(-1);
    setDraftFilters({ bhk: '', minRent: undefined, maxRent: undefined, locality: '', furnishing: '' });
    setFilterType(''); setHomeType('');
    const clear = resetFiltersForSearchClear(draftCity);
    onSearch(draftCity, undefined, clear, { search: clear, explicit: emptyLandingPart(draftCity), searchLabel: '' });
  };
  const resetExplicit = () => {
    setDraftFilters({ bhk: '', minRent: undefined, maxRent: undefined, locality: '', furnishing: '' });
    setFilterType('');
    commit({ ...landingState, explicit: emptyLandingPart(draftCity) }, draftCity);
  };
  const chooseCity = (name: string) => {
    setDraftCity(name); setCityOpen(false); setCityFilter(''); setQuery(''); setSelected(null);
    setDraftFilters({ bhk: '', minRent: undefined, maxRent: undefined, locality: '', furnishing: '' }); setHomeType('');
    const clean = resetFiltersForManualCityChange(name);
    onSearch(clean.city, undefined, clean, { search: emptyLandingPart(name), explicit: emptyLandingPart(name), searchLabel: '' });
  };
  const chooseSuggestion = (item: RentalSuggestion) => {
    try { propertyService.reportSearchFeedback({ eventType: 'SUGGESTION_SELECTED', candidateTerm: query.trim(), canonicalLocality: item.locality || undefined, canonicalCity: item.city || undefined, selectedType: item.type, selectedRank: suggestions.indexOf(item), bhkKey: item.bhk || undefined }); } catch { /* Telemetry is optional. */ }
    setSelected(item); setQuery(item.label); setDraftCity(item.city); setSuggestionsOpen(false);
    execute(item.label, item, item.city);
  };
  return <form id="hero-search-surface" className="lp-search" role="search" aria-label={draftCity ? `Search rental homes in ${draftCity}` : 'Search rental homes'} onSubmit={event => { event.preventDefault(); execute(); }}>
    <div className="lp-search-row">
      <div className="lp-city" ref={cityRef}>
        <button type="button" className="lp-segment lp-city-trigger" aria-haspopup="listbox" aria-expanded={cityOpen} onClick={() => { setCityOpen(v => !v); setCityIndex(Math.max(0, cityOptions.findIndex(option => option.name === draftCity))); }}><span className="lp-segment-label">City</span><span className="lp-segment-value"><MapPin size={15} className="lp-city-pin" aria-hidden="true"/>{draftCity || 'Choose city'}<ChevronDown size={15} className="lp-chevron" aria-hidden="true"/></span></button>
        {cityOpen && <div className="lp-popover lp-city-popover"><label className="lp-city-search"><Search size={15} aria-hidden="true"/><span className="sr-only">Search cities</span><input autoFocus value={cityFilter} role="combobox" aria-expanded="true" aria-controls="lp-city-list" aria-activedescendant={cityOptions[cityIndex] ? `lp-city-${cityOptions[cityIndex].id}` : undefined} onChange={e => { setCityFilter(e.target.value); setCityIndex(0); }} onKeyDown={e => { if (e.key === 'ArrowDown') { e.preventDefault(); setCityIndex(i => Math.min(i + 1, cityOptions.length - 1)); } else if (e.key === 'ArrowUp') { e.preventDefault(); setCityIndex(i => Math.max(i - 1, 0)); } else if (e.key === 'Enter' && cityOptions[cityIndex]) { e.preventDefault(); chooseCity(cityOptions[cityIndex].name); } }} placeholder="Search cities"/></label><ul id="lp-city-list" className="lp-city-list" role="listbox" aria-label="Cities">{cityOptions.map((option, index) => <li key={option.id} id={`lp-city-${option.id}`} role="option" aria-selected={option.name === draftCity} className={`lp-city-option ${index === cityIndex ? 'is-active' : ''}`} onPointerEnter={() => setCityIndex(index)} onClick={() => chooseCity(option.name)}><span><strong>{option.name}</strong></span>{option.name === draftCity && <Check size={16}/>}</li>)}{!cityOptions.length && <li className="lp-city-empty">No matching city</li>}</ul></div>}
      </div>
      <span className="lp-search-divider lp-divider-city" aria-hidden="true"/>
      <div className="lp-search-query" ref={queryRef}><label htmlFor="lp-query" className="lp-segment-label">Search</label><div className="lp-query-field"><Search size={17} className="lp-query-icon" aria-hidden="true"/><input ref={searchRef} id="lp-query" value={query} autoComplete="off" enterKeyHint="search" role="combobox" aria-autocomplete="list" aria-expanded={suggestionsOpen && suggestionState !== 'idle'} aria-controls="lp-suggestion-panel" aria-activedescendant={suggestionsOpen && active >= 0 ? `lp-suggestion-${active}` : undefined} onFocus={() => setSuggestionsOpen(true)} onChange={e => { setQuery(e.target.value); setSelected(null); setSuggestionsOpen(true); }} onKeyDown={e => { if (e.nativeEvent.isComposing) return; if (e.key === 'Escape') { setSuggestionsOpen(false); setActive(-1); } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); setSuggestionsOpen(true); setActive(v => suggestions.length ? (e.key === 'ArrowDown' ? (v + 1) % suggestions.length : (v < 0 ? suggestions.length - 1 : (v - 1 + suggestions.length) % suggestions.length)) : -1); } else if (e.key === 'Enter' && suggestionsOpen && active >= 0 && suggestions[active]) { e.preventDefault(); chooseSuggestion(suggestions[active]); } }} placeholder=" " aria-description="Search locality, landmark, budget or BHK"/><span className="lp-query-hint" aria-hidden="true"><span className="lp-query-hint-long">Try “2 BHK near a landmark”</span><span className="lp-query-hint-short">Area, landmark or “2 BHK”</span></span>{query && <button type="button" className="lp-query-clear" aria-label="Clear search and filters" onClick={clearAllDiscovery}><X size={15}/></button>}</div>
        {suggestionsOpen && suggestionState !== 'idle' && <div id="lp-suggestion-panel" className="lp-popover lp-suggestions">{suggestionState === 'loading' && <ul className="lp-suggestion-list" aria-label="Loading suggestions" aria-busy="true">{[0,1,2].map(i => <li className="lp-suggestion is-skeleton" key={i}><span className="lp-skel lp-skel-circle"/><span className="lp-suggestion-copy"><span className="lp-skel lp-skel-line"/></span></li>)}</ul>}{suggestionState === 'unavailable' && <div className="lp-suggestion-message" role="status"><WifiOff size={18}/><p><strong>Suggestions are unavailable right now.</strong><span>You can still search. Press Enter to find homes in {draftCity}.</span></p></div>}{suggestionState === 'empty' && <div className="lp-suggestion-message" role="status"><Search size={18}/><p><strong>No suggestions for “{query.trim()}”</strong><span>Try a locality, landmark or home type. Press Enter to search anyway.</span></p></div>}{suggestionState === 'ready' && <ul id="lp-suggestion-list" role="listbox" aria-label="Search suggestions" className="lp-suggestion-list">{suggestions.some(s => s.type === 'LOCALITY' || s.type === 'ENTITY_MATCH') && <li className="lp-suggestion-group" role="presentation">Places in {draftCity}</li>}{suggestions.map((item, index) => <li key={`${item.type}-${item.label}-${index}`} id={`lp-suggestion-${index}`} role="option" aria-selected={index === active} className={`lp-suggestion ${index === active ? 'is-active' : ''}`} onPointerEnter={() => setActive(index)} onPointerDown={event => event.preventDefault()} onClick={() => chooseSuggestion(item)}><span className="lp-suggestion-icon"><MapPin size={15}/></span><span className="lp-suggestion-copy"><strong>{item.label}</strong><small>{item.locality ? `Locality · ${item.city}` : item.type === 'CITY' ? 'City' : 'Search this requirement'}</small></span><ArrowUpLeft size={15} className="lp-suggestion-fill"/></li>)}</ul>}<div className="lp-suggestion-foot" aria-hidden="true"><span><kbd>↑</kbd><kbd>↓</kbd> to move</span><span><kbd><CornerDownLeft size={11}/></kbd> to search</span><span><kbd>esc</kbd> to close</span></div></div>}
      </div>
      <span className="lp-search-divider lp-divider-wide" aria-hidden="true"/>
      <div className="lp-search-secondary"><label className="lp-segment lp-type"><span className="lp-segment-label">Home type</span><span className="lp-segment-value"><select value={homeType} onChange={e => setHomeType(e.target.value as RentalPropertyType | '')} aria-label="Home type"><option value="">Any type</option>{allTypes.map(type => <option key={type} value={type}>{PROPERTY_TYPE_LABELS[type]}</option>)}</select><ChevronDown size={15} className="lp-chevron" aria-hidden="true"/></span></label><div className="lp-filters-anchor" ref={filtersRef}><button type="button" className={`lp-filter-trigger ${count ? 'has-filters' : ''}`} aria-haspopup="dialog" aria-expanded={filtersOpen} onClick={() => { if (!filtersOpen) { setDraftFilters({ bhk: landingState.explicit.bhk || '', minRent: landingState.explicit.minRent, maxRent: landingState.explicit.maxRent, locality: landingState.explicit.sector || '', furnishing: landingState.explicit.furnishing || '' }); setFilterType(landingState.explicit.propertyType || ''); } setFiltersOpen(v => !v); }}><SlidersHorizontal size={16}/><span className="lp-filter-label">Filters</span>{count > 0 && <span className="lp-filter-count">{count}<span className="sr-only"> active</span></span>}</button>{filtersOpen && <><div className="lp-sheet-backdrop" onClick={() => setFiltersOpen(false)}/><div className="lp-popover lp-filters" role="dialog" aria-modal="true" aria-labelledby="lp-filters-title"><span className="lp-sheet-handle" aria-hidden="true"/><div className="lp-filters-head"><h2 ref={headingRef} tabIndex={-1} id="lp-filters-title">Filters</h2><button type="button" className="lp-icon-button" aria-label="Close filters" onClick={() => setFiltersOpen(false)}><X size={18}/></button></div><div className="lp-filters-body"><fieldset className="lp-filter-group"><legend>Monthly rent</legend><div className="lp-chip-row">{budgets.map(band => <label key={band.label} className={`lp-chip ${draftFilters.minRent === band.min && draftFilters.maxRent === band.max ? 'is-selected' : ''}`}><input className="sr-only" type="radio" name="lp-budget" checked={draftFilters.minRent === band.min && draftFilters.maxRent === band.max} onChange={() => setDraftFilters(v => ({ ...v, minRent: band.min, maxRent: band.max }))}/>{band.label}</label>)}</div></fieldset><fieldset className="lp-filter-group"><legend>BHK</legend><div className="lp-chip-row lp-chip-row-even">{['','1RK','1BHK','2BHK','3BHK','4BHK'].map(bhk => <label key={bhk} className={`lp-chip ${draftFilters.bhk === bhk ? 'is-selected' : ''}`}><input className="sr-only" type="radio" name="lp-bhk" checked={draftFilters.bhk === bhk} onChange={() => setDraftFilters(v => ({ ...v, bhk }))}/>{bhk ? bhk.replace(/(BHK|RK)$/, ' $1') : 'Any BHK'}</label>)}</div></fieldset><fieldset className="lp-filter-group"><legend>Home type</legend><div className="lp-chip-row"><label className={`lp-chip ${!filterType ? 'is-selected' : ''}`}><input className="sr-only" type="radio" name="lp-home-type" checked={!filterType} onChange={() => setFilterType('')}/>Any type</label>{allTypes.map(type => <label key={type} className={`lp-chip ${filterType === type ? 'is-selected' : ''}`}><input className="sr-only" type="radio" name="lp-home-type" checked={filterType === type} onChange={() => setFilterType(type)}/>{PROPERTY_TYPE_LABELS[type]}</label>)}</div></fieldset><fieldset className="lp-filter-group"><legend>Furnishing</legend><div className="lp-chip-row"><label className={`lp-chip ${!draftFilters.furnishing ? 'is-selected' : ''}`}><input className="sr-only" type="radio" name="lp-furnishing" checked={!draftFilters.furnishing} onChange={() => setDraftFilters(v => ({ ...v, furnishing: '' }))}/>Any furnishing</label>{allFurnishings.map(value => <label key={value} className={`lp-chip ${draftFilters.furnishing === value ? 'is-selected' : ''}`}><input className="sr-only" type="radio" name="lp-furnishing" checked={draftFilters.furnishing === value} onChange={() => setDraftFilters(v => ({ ...v, furnishing: value }))}/>{FURNISHING_LABELS[value]}</label>)}</div></fieldset><label className="lp-filter-group lp-filter-locality"><span>Locality or area</span><input type="text" value={draftFilters.locality} onChange={event => setDraftFilters(v => ({ ...v, locality: event.target.value }))} placeholder="Any locality" autoComplete="off" /></label></div><div className="lp-filters-foot"><button type="button" className="lp-btn lp-btn-text" onClick={resetExplicit}>Reset</button><button type="button" className="lp-btn lp-btn-primary" onClick={applyFilters}>Show homes</button></div></div></>}</div></div>
      <button type="submit" className="lp-btn lp-btn-primary lp-search-submit" aria-busy={loading}>{loading ? <LoaderCircle size={17} className="lp-spin"/> : <Search size={17}/>}<span>{loading ? 'Finding homes' : 'Find homes'}</span></button>
    </div>
  </form>;
}

function Updated({ value }: { value: Property['updatedAt'] }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 60_000); return () => window.clearInterval(timer); }, []);
  if (!parsePropertyTimestamp(value)) return null;
  const label = formatPropertyRelativeTime(value, now);
  const exact = formatExactPropertyTimestampIST(value);
  if (!label) return null;
  const short = label.replace(' minutes ago', 'm ago').replace(' minute ago', 'm ago').replace(' hours ago', 'h ago').replace(' hour ago', 'h ago').replace(' days ago', 'd ago').replace('yesterday', '1d ago').replace(' weeks ago', 'w ago').replace(' week ago', 'w ago').replace(' months ago', 'mo ago').replace(' month ago', 'mo ago').replace(' years ago', 'y ago').replace(' year ago', 'y ago');
  return <p className="lp-updated"><Clock3 size={13} aria-hidden="true"/><time dateTime={String(value)} title={exact || undefined}><span className="lp-updated-long">Updated {label}</span><span className="lp-updated-short" aria-hidden="true">{short}</span></time></p>;
}

function ListingCard({ home, onOpen, onSave, priority, authenticated, saved, favoriteReady, favoritePending }: { home: Property; onOpen: () => void; onSave: () => void; priority: boolean; authenticated: boolean; saved: boolean; favoriteReady: boolean; favoritePending: boolean }) {
  const [photoFailed, setPhotoFailed] = useState(false);
  const [hover, setHover] = useState(false);
  const [videoUrl, setVideoUrl] = useState<string | null>(() => resolvePropertyVideoUrl(home));
  const [videoPlaying, setVideoPlaying] = useState(false);
  const [videoFailed, setVideoFailed] = useState(false);
  const video = useRef<HTMLVideoElement>(null);
  const hasVideo = Boolean(home._hasVideo || resolvePropertyVideoUrl(home));
  const image = home.images?.find(Boolean);
  useEffect(() => { setPhotoFailed(false); setVideoUrl(resolvePropertyVideoUrl(home)); setVideoPlaying(false); setVideoFailed(false); }, [home.id, home.images?.[0], home.videoUrl]);
  useEffect(() => {
    if (!hover || !hasVideo || videoUrl || videoFailed) return;
    let live = true;
    propertyService.getPublicProperty(home.id).then(detail => {
      if (!live) return;
      const url = resolvePropertyVideoUrl(detail);
      if (url) setVideoUrl(url); else setVideoFailed(true);
    }).catch(() => { if (live) setVideoFailed(true); });
    return () => { live = false; };
  }, [hover, hasVideo, videoUrl, videoFailed, home.id]);
  useEffect(() => {
    const player = video.current;
    if (!player || !hover) { player?.pause(); setVideoPlaying(false); return; }
    void player.play().catch(() => setVideoPlaying(false));
  }, [hover, videoUrl]);
  const locality = landingLocality(home);
  const location = [locality, home.city?.trim()].filter(Boolean).join(', ');
  const title = landingPropertyTitle(home);
  const metadata = [home.totalAreaSqFt > 0 ? `${home.totalAreaSqFt.toLocaleString('en-IN')} sq ft` : '', home.furnishingStatus?.replace(/_/g, ' ')].filter(Boolean).join(' · ');
  return <article id={`property-card-${home.id}`} className="lp-card" onPointerEnter={event => { if (event.pointerType === 'mouse' && window.matchMedia('(hover: hover) and (prefers-reduced-motion: no-preference)').matches) setHover(true); }} onPointerLeave={() => setHover(false)}>
    <div className="lp-card-media">{image && !photoFailed ? <img src={buildCloudinaryUrl(image, 'DISCOVERY_CARD')} alt={`${title} in ${location}`} loading={priority ? 'eager' : 'lazy'} onError={() => setPhotoFailed(true)}/> : <div className="lp-no-media">{photoFailed ? <ImageOff size={22}/> : <Home size={22}/>}<span>{photoFailed ? 'Photo unavailable right now' : 'Photos not added yet'}</span></div>}{videoUrl && hover && <video ref={video} className={`lp-card-video ${videoPlaying ? 'is-visible' : ''}`} src={videoUrl} muted loop playsInline preload="metadata" onPlaying={() => setVideoPlaying(true)} onError={() => { setVideoFailed(true); setVideoPlaying(false); }} aria-hidden="true" tabIndex={-1}/>}{hasVideo && <span className="lp-media-badge"><Play size={11} fill="currentColor" aria-hidden="true"/>Video</span>}<span className="lp-card-cue" aria-hidden="true">View home</span></div>
    <div className="lp-card-sheet"><h3 className="lp-card-title">{title}</h3><p className="lp-card-rent">{home.listingType === 'SALE' ? rent(home.askingPrice || 0) : rent(home.monthlyRent)}{home.listingType !== 'SALE' && <span>/month</span>}</p><p className="lp-card-location"><MapPin size={13} aria-hidden="true"/><span>{locality}{locality && home.city ? ', ' : ''}<span className="lp-card-city">{home.city?.trim() || ''}</span></span></p>{metadata && <p className="lp-card-meta">{metadata}</p>}<Updated value={home.updatedAt}/></div>
    <button type="button" className="lp-card-hit" onClick={onOpen} aria-label={`Preview ${title} in ${location}`}/><button type="button" className={`lp-save ${saved ? 'is-saved' : ''}`} aria-label={authenticated ? favoriteReady ? `${saved ? 'Remove' : 'Save'} ${title} ${saved ? 'from' : 'to'} saved homes` : `Checking saved status for ${title}` : `Sign in to save ${title}`} aria-pressed={favoriteReady ? saved : undefined} disabled={authenticated && (!favoriteReady || favoritePending)} onClick={onSave}><Heart size={17} fill={saved ? 'currentColor' : 'none'} aria-hidden="true"/></button>
  </article>;
}

function ListingSkeleton() { return <div className="lp-card is-skeleton" aria-hidden="true"><div className="lp-card-media lp-skel"/><div className="lp-card-sheet"><span className="lp-skel lp-skel-line" style={{ width: '62%' }}/><span className="lp-skel lp-skel-line" style={{ width: '40%' }}/><span className="lp-skel lp-skel-line lp-skel-thin" style={{ width: '70%' }}/></div></div>; }

function Discovery({ city, landingState, properties, loading, error, hasMore, loadingMore, loadMoreError, onSearch, onRetry, onLoadMore, onOpenProperty, onSaveFavorite, authenticated = false, savedPropertyIds, favoriteReady = false, favoriteError = false, onRetryFavorites, favoritePendingIds, focusCity }: LandingV0Props & { focusCity: () => void }) {
  const explicitCriteria = landingAppliedCriteria(landingState.explicit);
  const searchActive = Boolean(landingState.searchLabel || landingAppliedCriteria(landingState.search).length);
  const criteria = explicitCriteria.length + (searchActive ? 1 : 0);
  const hasFilters = criteria > 0;
  const applyState = (state: LandingState) => {
    const effective = landingEffectiveFilters(state, city);
    onSearch(city, effective.sector, effective, state);
  };
  const clear = () => applyState({ search: emptyLandingPart(city), explicit: emptyLandingPart(city), searchLabel: '' });
  const remove = (key: Parameters<typeof removeLandingCriterion>[1]) => applyState({ ...landingState, explicit: removeLandingCriterion(landingState.explicit, key) });
  return <section id="homes" className="lp-section lp-discovery" aria-labelledby="lp-homes-title" aria-busy={loading}><div className="lp-container"><div className="lp-section-head"><div><h2 id="lp-homes-title" className="lp-section-title" tabIndex={-1}>Homes {city ? `in ${city}` : 'across cities'}</h2><p className="lp-section-sub">{criteria ? 'Homes matching your search' : 'Recently updated rentals'}</p></div><a href="#homes" className="lp-btn lp-btn-text lp-head-link">Explore homes<ArrowRight size={15}/></a></div>
    {authenticated && favoriteError && <p className="lp-favorite-error" role="alert">Saved homes are unavailable right now. <button type="button" onClick={onRetryFavorites}>Try again</button></p>}
    {criteria > 0 && <div className="lp-criteria" aria-label="Applied search">{searchActive && <span className="lp-chip lp-chip-static lp-chip-search">Search: {landingState.searchLabel || 'Selected requirement'}</span>}{explicitCriteria.map(item => <button key={item.key} type="button" className="lp-chip lp-chip-static" aria-label={`Remove ${item.label} filter`} onClick={() => remove(item.key)}>{item.label}<X size={13} aria-hidden="true"/></button>)}<button type="button" className="lp-chip lp-chip-clear" onClick={clear}><X size={15} aria-hidden="true"/>Clear all</button></div>}
    {loading ? <div className="lp-grid" role="status" aria-label="Loading homes">{Array.from({ length: 6 }, (_, i) => <ListingSkeleton key={i}/>)}</div> : error ? <div className="lp-state" role="alert"><span className="lp-state-icon is-warning"><CloudOff size={22}/></span><h3>We couldn’t load homes right now</h3><p>This is usually temporary. Check your connection and try again.</p><div className="lp-state-actions"><button type="button" className="lp-btn lp-btn-secondary" onClick={onRetry}><RotateCcw size={16}/>Try again</button></div></div> : properties.length ? <><ul className="lp-grid" aria-label={city ? `Homes in ${city}` : 'Homes across cities'}>{properties.map((home, index) => <li key={home.id}><ListingCard home={home} priority={index < 3} onOpen={() => onOpenProperty(home)} onSave={() => onSaveFavorite(home)} authenticated={authenticated} saved={savedPropertyIds?.has(home.id) || false} favoriteReady={!authenticated || favoriteReady} favoritePending={favoritePendingIds?.has(home.id) || false}/></li>)}</ul><p className="lp-visit-cue"><CalendarCheck2 size={16}/>Found one you like? Open it to request a visit. Pathome coordinates the time with you.</p>{(hasMore || loadMoreError) && <div className="lp-load-more">{loadMoreError && <p role="alert">Couldn’t load more homes. Please try again.</p>}<button type="button" className="lp-btn lp-btn-secondary" onClick={onLoadMore} disabled={loadingMore}>{loadingMore ? 'Loading homes…' : loadMoreError ? 'Retry' : 'Show more homes'}</button></div>}</> : <div className="lp-state" role="status"><span className="lp-state-icon"><SearchX size={22}/></span><h3>No homes found</h3><p>Try another location or adjust your filters.</p><div className="lp-state-actions"><button type="button" className="lp-btn lp-btn-secondary" onClick={focusCity}><MapPinned size={16}/>Change location</button>{hasFilters && <button type="button" className="lp-btn lp-btn-text" onClick={clear}>Clear filters</button>}</div></div>}
  </div></section>;
}

const principles = [
  { icon: Camera, title: 'Real property media', body: 'Explore the photos and videos each listing provides, taken of the home itself.' },
  { icon: ListChecks, title: 'Structured details', body: 'Rent, home type, BHK and locality appear the same way on every listing, so homes are easy to compare.' },
  { icon: Route, title: 'Coordinated visits', body: 'Request a visit and Pathome arranges it. When suitable homes are nearby, they can join the same trip.' },
  { icon: Activity, title: 'Visible progress', body: 'Follow your visit request as it moves forward, without chasing calls.' },
];
const steps = [
  { title: 'Search naturally', body: 'Type it the way you’d say it: a locality, a landmark, a budget, a BHK.' },
  { title: 'Explore real homes', body: 'Compare listings through the photos, videos and details each one provides.' },
  { title: 'Request a visit', body: 'Choose a home you like and ask to see it. No back-and-forth calls.' },
  { title: 'Visit suitable homes', body: 'Pathome coordinates the visit and can add suitable nearby homes when available.' },
];
const stops = [
  { image: '/pathome-apartment.webp', label: '2 BHK Apartment' },
  { image: '/landing/listing-living.webp', label: '2 BHK Flat' },
  { image: '/pathome-house-landing.webp', label: '3 BHK House' },
];

export function LandingV0(props: LandingV0Props) {
  const { city, filters, properties, loading, error, onSearch, onSignIn, onListProperty } = props;
  const focusCity = useCallback(() => { window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' }); window.setTimeout(() => { const trigger = document.querySelector<HTMLButtonElement>('.lp-city-trigger'); trigger?.focus({ preventScroll: true }); if (trigger?.getAttribute('aria-expanded') === 'false') trigger.click(); }, 300); }, []);
  const cityLabel = city || 'available cities';
  return <div className="lp"><a href="#homes" className="lp-skip">Skip to homes</a>{!props.authenticated && <LandingNav onSignIn={onSignIn} onListProperty={onListProperty}/>}<main>
    <section className="lp-hero" aria-labelledby="lp-hero-title"><div className="lp-container"><div className="lp-hero-grid"><div className="lp-hero-copy"><p className="lp-eyebrow"><span className="lp-eyebrow-line" aria-hidden="true"/>Rental homes in {cityLabel}</p><h1 id="lp-hero-title" className="lp-hero-title text-balance">Find a home that fits <em>the way you live.</em></h1><p className="lp-hero-lede text-pretty">Search the way you’d say it, explore each home through its own photos, and request a visit when one feels right.</p></div><div className="lp-hero-media" aria-hidden="true"><div className="lp-hero-photo lp-hero-photo-main"><img src="/landing/hero-living.webp" alt=""/></div><div className="lp-hero-photo lp-hero-photo-inset"><img src="/pathome-kitchen.webp" alt=""/></div><p className="lp-hero-note"><CalendarCheck2 size={14}/>Request a visit from any listing</p></div></div><SearchInstrument city={city} landingState={props.landingState} loading={loading} onSearch={onSearch}/><ul className="lp-hero-points" aria-label="What Pathome offers"><li><span className="lp-hero-point-icon"><Camera size={15}/></span><span>Real property media</span></li><li><span className="lp-hero-point-icon"><Route size={15}/></span><span>Nearby homes in one visit<span className="lp-point-extra">, when available</span></span></li><li><span className="lp-hero-point-icon"><CalendarCheck2 size={15}/></span><span>Track every visit request</span></li></ul></div></section>
    {props.latestDraft && props.onOpenDraft && <section className="lp-draft-continuation" aria-labelledby="lp-draft-title"><div className="lp-container"><div className="lp-draft-card"><div><span className="lp-draft-eyebrow">CONTINUE YOUR PROPERTY LISTING</span><h2 id="lp-draft-title">{props.latestDraft.title || 'Your property draft'}</h2><p>Draft · {props.latestDraft.completionPercent}% complete · {[props.latestDraft.locality, props.latestDraft.city].filter(Boolean).join(', ') || 'Location to add'}{props.latestDraft.monthlyRent !== null ? ` · ${rent(props.latestDraft.monthlyRent)}/month` : ''}</p><Updated value={props.latestDraft.updatedAt}/></div><div className="lp-draft-actions"><button type="button" className="lp-btn lp-btn-primary" onClick={() => props.onOpenDraft?.(props.latestDraft!.draftId)}>Resume draft<ArrowRight size={16}/></button>{(props.draftCount || 0) > 1 && <button type="button" className="lp-btn lp-btn-text" onClick={props.onViewAllDrafts}>View all drafts ({props.draftCount})</button>}</div></div></div></section>}
    <Discovery {...props} focusCity={focusCity}/>
    <div className="lp-signature" aria-hidden="true"><span className="lp-signature-line"/><p><span className="lp-signature-word">Pathome</span><span className="lp-signature-tag">Your Dreams, Our Efforts.</span></p><span className="lp-signature-line"/></div>
    <section id="why-us" className="lp-section lp-value" aria-labelledby="lp-value-title"><div className="lp-container lp-value-grid"><div className="lp-value-intro"><p className="lp-eyebrow"><span className="lp-eyebrow-line"/>Why Pathome</p><h2 id="lp-value-title" className="lp-section-title lp-title-lg text-balance">A calmer way to find a rental home.</h2><p className="lp-section-sub text-pretty">Pathome keeps the search honest and the next step clear, from the first search to the visit itself.</p></div><ul className="lp-principles">{principles.map(({ icon: Icon, title, body }) => <li key={title}><span className="lp-principle-icon" aria-hidden="true"><Icon size={18}/></span><h3>{title}</h3><p>{body}</p></li>)}</ul></div></section>
    <section id="how-it-works" className="lp-section lp-how" aria-labelledby="lp-how-title"><div className="lp-container"><div className="lp-section-head"><div><h2 id="lp-how-title" className="lp-section-title">How Pathome works</h2><p className="lp-section-sub">Four steps from searching to standing in the home.</p></div></div><ol className="lp-steps">{steps.map((step, index) => <li key={step.title}><span className="lp-step-number" aria-hidden="true">{index + 1}</span><h3>{step.title}</h3><p>{step.body}</p></li>)}</ol></div></section>
    <section className="lp-section lp-visit" aria-labelledby="lp-visit-title"><div className="lp-container"><div className="lp-visit-band"><div className="lp-visit-copy"><p className="lp-eyebrow lp-eyebrow-invert">Visit sessions</p><h2 id="lp-visit-title" className="lp-visit-title text-balance">See several suitable homes in one coordinated visit.</h2><p className="text-pretty">Request a visit for a home you like. When other suitable homes are nearby, Pathome can plan them into the same session, so one trip covers more of your shortlist.</p><a href="#homes" className="lp-btn lp-btn-invert">Find homes to visit<ArrowRight size={16}/></a></div><figure className="lp-route-figure"><ol className="lp-route" aria-label="Illustrative visit session with three nearby homes">{stops.map((stop, index) => <li key={stop.label} className="lp-route-stop"><span className="lp-route-photo"><img src={stop.image} alt=""/></span><span className="lp-route-label"><small>Stop {index + 1}</small>{stop.label}</span></li>)}</ol><figcaption className="lp-route-caption">Illustrative example. Actual sessions depend on nearby availability.</figcaption></figure></div></div></section>
    <section id="list-property" className="lp-section lp-owner" aria-labelledby="lp-owner-title"><div className="lp-container"><div className="lp-owner-card"><span className="lp-owner-photo"><img src="/pathome-balcony.webp" alt=""/></span><div className="lp-owner-copy"><p className="lp-eyebrow">For property owners</p><h2 id="lp-owner-title">Have a home to rent out?</h2><p className="text-pretty">List it on Pathome with the details tenants look for. You can rent and list from the same account.</p></div><button type="button" className="lp-btn lp-btn-secondary" onClick={onListProperty}>List your property<ArrowRight size={16}/></button></div></div></section>
  </main><footer className="lp-footer"><div className="lp-container lp-footer-grid"><div className="lp-footer-brand"><Brand/><p>Your Dreams, Our Efforts.</p></div><nav aria-label="Explore" className="lp-footer-group"><h2>Explore</h2><ul><li><a href="#homes">Find homes</a></li><li><a href="#how-it-works">How it works</a></li></ul></nav><nav aria-label="Owners" className="lp-footer-group"><h2>Owners</h2><ul><li><button type="button" onClick={onListProperty}>List your property</button></li></ul></nav><section aria-label="Pathome" className="lp-footer-group"><h2>Pathome</h2><ul><li><span>Help &amp; support</span></li><li><span>Privacy</span></li><li><span>Terms</span></li></ul></section></div><div className="lp-container lp-footer-base"><p>© {currentYear} Pathome</p><p>Rental homes, coordinated visits.</p></div></footer></div>;
}
