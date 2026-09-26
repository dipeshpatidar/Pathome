import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ArrowRight, Check, MapPin, Search, X } from 'lucide-react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { DISCOVERY_CITIES, findDiscoveryCity, matchesLocationQuery } from './discoveryLocations';

type LocationStep = 'city' | 'locality';

interface DiscoveryLocationDialogProps {
  open: boolean;
  step: LocationStep;
  onStepChange: (step: LocationStep) => void;
  city: string;
  locality: string;
  onCityChange: (city: string) => void;
  onLocalityChange: (locality: string) => void;
  onApply: () => void;
  onClose: () => void;
  cityOnly?: boolean;
  onCitySelected?: () => void;
}

export const DiscoveryLocationDialog: React.FC<DiscoveryLocationDialogProps> = ({
  open,
  step,
  onStepChange,
  city,
  locality,
  onCityChange,
  onLocalityChange,
  onApply,
  onClose,
  cityOnly = false,
  onCitySelected
}) => {
  const [query, setQuery] = useState('');
  const dialogRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const optionListRef = useRef<HTMLDivElement>(null);
  const applyRef = useRef<HTMLButtonElement>(null);
  const prefersReducedMotion = useReducedMotion();
  const selectedCity = findDiscoveryCity(city);
  const options = step === 'city' ? DISCOVERY_CITIES : selectedCity.localities;
  const filteredOptions = options.filter((option) =>
    matchesLocationQuery(typeof option === 'string' ? option : option.name, query)
  );

  const chooseOption = (name: string) => {
    if (step === 'city') {
      onCityChange(name);
      if (cityOnly) onCitySelected?.();
      else onStepChange('locality');
    } else {
      onLocalityChange(name);
      window.requestAnimationFrame(() => applyRef.current?.focus());
    }
  };

  useEffect(() => {
    if (!open) return;
    setQuery('');
    if (window.matchMedia('(min-width: 768px)').matches) {
      window.requestAnimationFrame(() => searchRef.current?.focus());
    } else {
      window.requestAnimationFrame(() => dialogRef.current?.focus());
    }
  }, [open, step]);

  useEffect(() => {
    if (!open) return undefined;
    const scrollY = window.scrollY;
    const previous = {
      position: document.body.style.position,
      top: document.body.style.top,
      width: document.body.style.width,
      overflow: document.body.style.overflow
    };
    document.body.style.position = 'fixed';
    document.body.style.top = `-${scrollY}px`;
    document.body.style.width = '100%';
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.position = previous.position;
      document.body.style.top = previous.top;
      document.body.style.width = previous.width;
      document.body.style.overflow = previous.overflow;
      window.scrollTo({ top: scrollY, left: 0, behavior: 'instant' });
    };
  }, [open]);

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    }
    if (event.key !== 'Tab') return;
    const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled])'
    ) || []);
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  const content = (
    <div className="fixed inset-0 z-[100] flex items-end justify-center sm:items-center sm:p-5">
      <motion.button
        type="button"
        aria-label="Close location search"
        onClick={onClose}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: prefersReducedMotion ? 0 : 0.18 }}
        className="absolute inset-0 h-full w-full cursor-default bg-slate-950/65"
        tabIndex={-1}
      />
      <motion.div
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby="discovery-dialog-title"
        onKeyDown={handleKeyDown}
        initial={prefersReducedMotion ? false : { opacity: 0, y: 18 }}
        animate={{ opacity: 1, y: 0 }}
        exit={prefersReducedMotion ? undefined : { opacity: 0, y: 12 }}
        transition={{ duration: prefersReducedMotion ? 0 : 0.2, ease: [0.16, 1, 0.3, 1] }}
        className="relative z-10 flex max-h-[92dvh] w-full min-w-0 flex-col overflow-hidden rounded-t-3xl border border-slate-200 bg-white shadow-2xl sm:max-h-[min(42rem,88dvh)] sm:max-w-[35rem] sm:rounded-3xl"
      >
        <div className="flex shrink-0 items-start justify-between gap-3 border-b border-slate-100 px-5 pb-4 pt-5 sm:px-6">
          <div className="min-w-0">
            <p className="text-xs font-bold uppercase tracking-[0.14em] text-emerald-700">Find your place</p>
            <h2 id="discovery-dialog-title" className="mt-1 font-['Outfit',sans-serif] text-xl font-bold text-slate-900 sm:text-2xl">
              {cityOnly ? 'Choose a city' : 'Where do you want to find your next home?'}
            </h2>
          </div>
          <button type="button" onClick={onClose} aria-label="Close search" className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-slate-600 transition-colors duration-150 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        {!cityOnly && <div className="grid shrink-0 grid-cols-2 gap-2 px-5 pt-4 sm:px-6">
          <button type="button" onClick={() => onStepChange('city')} aria-pressed={step === 'city'} className={`min-h-12 min-w-0 rounded-xl border px-3 text-left transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 ${step === 'city' ? 'border-emerald-600 bg-emerald-50' : 'border-slate-200 hover:border-slate-300'}`}>
            <span className="block text-[11px] font-semibold text-slate-500">City</span>
            <span className="block truncate text-sm font-bold text-slate-900">{selectedCity.name}</span>
          </button>
          <button type="button" onClick={() => onStepChange('locality')} aria-pressed={step === 'locality'} className={`min-h-12 min-w-0 rounded-xl border px-3 text-left transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 ${step === 'locality' ? 'border-emerald-600 bg-emerald-50' : 'border-slate-200 hover:border-slate-300'}`}>
            <span className="block text-[11px] font-semibold text-slate-500">Locality</span>
            <span className="block truncate text-sm font-bold text-slate-900">{locality || 'Any locality'}</span>
          </button>
        </div>}

        <div className="shrink-0 px-5 pb-3 pt-4 sm:px-6">
          <label htmlFor="discovery-location-query" className="sr-only">Search {step === 'city' ? 'cities' : `localities in ${selectedCity.name}`}</label>
          <div className="flex min-h-12 items-center gap-3 rounded-xl border border-slate-200 bg-slate-50 px-4 focus-within:border-emerald-500 focus-within:ring-2 focus-within:ring-emerald-500/20">
            <Search className="h-4 w-4 shrink-0 text-slate-500" aria-hidden="true" />
            <input
              ref={searchRef}
              id="discovery-location-query"
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={step === 'city' ? 'Search cities' : `Search in ${selectedCity.name}`}
              className="min-w-0 flex-1 bg-transparent py-3 text-base text-slate-900 outline-none placeholder:text-slate-500"
              onKeyDown={(event) => {
                if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                  const buttons = optionListRef.current?.querySelectorAll<HTMLButtonElement>('button');
                  const target = event.key === 'ArrowDown' ? buttons?.[0] : buttons?.[buttons.length - 1];
                  if (target) {
                    event.preventDefault();
                    target.focus();
                  }
                }
                if (event.key === 'Enter' && filteredOptions.length === 1) {
                  event.preventDefault();
                  chooseOption(typeof filteredOptions[0] === 'string' ? filteredOptions[0] : filteredOptions[0].name);
                }
              }}
            />
          </div>
        </div>

        <p className="sr-only" role="status">{filteredOptions.length} matching {step === 'city' ? 'cities' : 'localities'}</p>
        <div
          ref={optionListRef}
          onKeyDown={(event) => {
            if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
            const buttons = Array.from(optionListRef.current?.querySelectorAll<HTMLButtonElement>('button') || []);
            const currentIndex = buttons.findIndex((button) => button === document.activeElement);
            const nextIndex = currentIndex + (event.key === 'ArrowDown' ? 1 : -1);
            if (nextIndex >= 0 && nextIndex < buttons.length) {
              event.preventDefault();
              buttons[nextIndex].focus();
            } else if (event.key === 'ArrowUp' && nextIndex < 0) {
              event.preventDefault();
              searchRef.current?.focus();
            }
          }}
          className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 pb-4 sm:px-6"
        >
          {step === 'locality' && !query && (
            <button type="button" onClick={() => chooseOption('')} aria-pressed={!locality} className="flex min-h-12 w-full items-center justify-between gap-3 rounded-xl px-3 text-left text-sm font-semibold text-slate-800 transition-colors duration-150 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">
              <span>Any locality</span>
              {!locality && <Check className="h-4 w-4 shrink-0 text-emerald-700" aria-hidden="true" />}
            </button>
          )}
          {filteredOptions.length ? filteredOptions.map((option) => {
            const name = typeof option === 'string' ? option : option.name;
            const selected = step === 'city' ? selectedCity.name === name : locality === name;
            const comingSoon = !cityOnly && typeof option !== 'string' && !option.hasHomes;
            return (
              <button
                key={name}
                type="button"
                onClick={() => chooseOption(name)}
                aria-pressed={selected}
                className="flex min-h-12 w-full items-center justify-between gap-3 rounded-xl px-3 text-left text-sm font-semibold text-slate-800 transition-colors duration-150 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600"
              >
                <span className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1"><MapPin className="h-4 w-4 shrink-0 text-slate-400" aria-hidden="true" /><span className="break-words">{name}</span>{comingSoon && <span className="text-xs font-medium text-slate-500">Coming soon</span>}</span>
                {selected && <Check className="h-4 w-4 shrink-0 text-emerald-700" aria-hidden="true" />}
              </button>
            );
          }) : <p className="px-3 py-6 text-sm text-slate-600">No matching {step === 'city' ? 'cities' : 'localities'}.</p>}
        </div>

        {!cityOnly && <div className="flex shrink-0 items-center justify-between gap-3 border-t border-slate-100 bg-white px-5 py-4 pb-[max(1rem,env(safe-area-inset-bottom))] sm:px-6 sm:pb-5">
          <p className="min-w-0 truncate text-sm font-medium text-slate-600">{selectedCity.name} · {locality || 'Any locality'}</p>
          <button ref={applyRef} type="button" onClick={onApply} className="inline-flex min-h-11 shrink-0 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-bold text-white transition-colors duration-150 hover:bg-emerald-700 active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 focus-visible:ring-offset-2">
            Show homes <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>}
      </motion.div>
    </div>
  );

  return createPortal(<AnimatePresence>{open && content}</AnimatePresence>, document.body);
};
