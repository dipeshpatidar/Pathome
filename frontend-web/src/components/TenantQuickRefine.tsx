import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Armchair, BedDouble, Building2, Home, LoaderCircle, SlidersHorizontal, X
} from 'lucide-react';
import { discoverySearchKey, formatRentDisplay, RentalSearchFilters } from '../utils/rentalSearch';
import {
  QUICK_REFINE_BHK_OPTIONS, QUICK_REFINE_FURNISHING, QUICK_REFINE_PROPERTY_TYPES,
  QUICK_REFINE_CHIP_REMOVE_TARGET_PX, QUICK_REFINE_RENT_SLIDER_MAX_POSITION,
  QuickRefineDimension, clearQuickRefineFilters,
  quickRefineActiveCount, quickRefineChipLabels, quickRefineOptionIsSelected,
  quickRefineResultSummary, updateQuickRefineFilter,
  applyQuickRefineRentSliderChanges, quickRefineRentValues,
  quickRefineRentToSliderPosition, quickRefineSliderPositionToRent,
  updateQuickRefineRentSlider,
  type QuickRefineRentBounds, type QuickRefineRentThumb, type QuickRefineRentValues
} from '../utils/tenantQuickRefine';

type DiscoveryState = 'LOADING' | 'READY' | 'ERROR';

interface QuickRefineBaseProps {
  filters: RentalSearchFilters;
  discoveryCity: string;
  discoveryState: DiscoveryState;
  discoveryLoadedKey: string | null;
  homesLoaded: number;
  rentBounds: QuickRefineRentBounds;
  onSearchHomes: (filters: RentalSearchFilters) => void;
}

const focusClass = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700';

const useRentSliderControls = (
  filters: RentalSearchFilters,
  bounds: QuickRefineRentBounds,
  onSearchHomes: (filters: RentalSearchFilters) => void
) => {
  const initialValues = quickRefineRentValues(filters, bounds);
  const [values, setValues] = useState<QuickRefineRentValues>(initialValues);
  const valuesRef = useRef(values);
  const filtersRef = useRef(filters);
  const boundsRef = useRef(bounds);
  const onSearchHomesRef = useRef(onSearchHomes);
  const timerRef = useRef<number | null>(null);
  const changedThumbsRef = useRef<Set<QuickRefineRentThumb>>(new Set());

  useEffect(() => {
    filtersRef.current = filters;
    boundsRef.current = bounds;
    onSearchHomesRef.current = onSearchHomes;
  }, [bounds, filters, onSearchHomes]);

  const cancelPending = () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    timerRef.current = null;
    changedThumbsRef.current.clear();
  };

  useEffect(() => {
    cancelPending();
    const next = quickRefineRentValues(filters, bounds);
    valuesRef.current = next;
    setValues(next);
    return undefined;
  }, [filters.minRent, filters.maxRent, bounds.min, bounds.max, bounds.step]);

  useEffect(() => () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
  }, []);

  const commitPending = () => {
    if (!changedThumbsRef.current.size) return;
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    const changed = [...changedThumbsRef.current];
    changedThumbsRef.current.clear();
    timerRef.current = null;
    onSearchHomesRef.current(applyQuickRefineRentSliderChanges(
      filtersRef.current, boundsRef.current, valuesRef.current, changed
    ));
  };

  const change = (thumb: QuickRefineRentThumb, rawValue: number, immediate = false) => {
    const updated = updateQuickRefineRentSlider(
      filtersRef.current, boundsRef.current, valuesRef.current, thumb, rawValue
    );
    valuesRef.current = updated.values;
    setValues(updated.values);
    changedThumbsRef.current.add(thumb);
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    if (immediate) {
      commitPending();
    } else {
      timerRef.current = window.setTimeout(commitPending, 320);
    }
  };

  return { values, change, commitPending, cancelPending };
};

const FilterOption: React.FC<{
  label: string;
  selected: boolean;
  icon: React.ReactNode;
  onClick: () => void;
}> = ({ label, selected, icon, onClick }) => (
  <button type="button" aria-pressed={selected} onClick={onClick}
      className={`min-h-11 rounded-full p-0.5 transition-transform duration-200 hover:-translate-y-px active:translate-y-0 motion-reduce:transform-none motion-reduce:transition-none ${focusClass}`}>
      <span className={`inline-flex h-9 items-center gap-1.5 rounded-full border px-2.5 text-xs font-medium transition-[background-color,border-color,box-shadow,color] duration-200 ${selected
      ? 'border-[#c8a85f] bg-[#0b674b] text-[#fffdf5] shadow-[0_3px_9px_-7px_rgba(5,45,31,.7),inset_0_1px_0_rgba(255,255,255,.16)]'
      : 'border-[#e5e3da] bg-[#fffefa] text-slate-700 hover:border-emerald-800/30 hover:bg-white'}`}>
      <span className={selected ? 'text-[#f5e8c6]' : 'text-emerald-800'} aria-hidden="true">{icon}</span>
      {label}
    </span>
  </button>
);

const FilterGroup: React.FC<{
  title: string;
  children: React.ReactNode;
}> = ({ title, children }) => (
  <fieldset className="min-w-0">
    <legend className="mb-2 text-[11px] font-bold uppercase tracking-[0.13em] text-slate-600">{title}</legend>
    <div className="flex min-w-0 flex-wrap gap-1">{children}</div>
  </fieldset>
);

const QuickRefineOptions: React.FC<{
  filters: RentalSearchFilters;
  onToggle: (dimension: 'bhk' | 'propertyType' | 'furnishing', value: string) => void;
}> = ({ filters, onToggle }) => (
  <div className="space-y-3.5">
    <FilterGroup title="BHK">
      {QUICK_REFINE_BHK_OPTIONS.map(option => <FilterOption key={option.value} label={option.label}
        icon={<BedDouble size={13} />} selected={quickRefineOptionIsSelected(filters, 'bhk', option.value)}
        onClick={() => onToggle('bhk', option.value)} />)}
    </FilterGroup>
    <FilterGroup title="Property type">
      {QUICK_REFINE_PROPERTY_TYPES.map((option, index) => <FilterOption key={option.value} label={option.label}
        icon={index === 0 ? <Building2 size={13} /> : <Home size={13} />}
        selected={quickRefineOptionIsSelected(filters, 'propertyType', option.value)}
        onClick={() => onToggle('propertyType', option.value)} />)}
    </FilterGroup>
    <FilterGroup title="Furnishing">
      {QUICK_REFINE_FURNISHING.map(option => <FilterOption key={option.value} label={option.label}
        icon={<Armchair size={13} />} selected={quickRefineOptionIsSelected(filters, 'furnishing', option.value)}
        onClick={() => onToggle('furnishing', option.value)} />)}
    </FilterGroup>
  </div>
);

const rentRangeLabel = (bounds: QuickRefineRentBounds, values: QuickRefineRentValues) => {
  const hasMin = values.min > bounds.min;
  const hasMax = values.max < bounds.max;
  if (hasMin && hasMax) return `₹${formatRentDisplay(values.min)} – ₹${formatRentDisplay(values.max)}`;
  if (hasMin) return `From ₹${formatRentDisplay(values.min)}`;
  if (hasMax) return `Up to ₹${formatRentDisplay(values.max)}`;
  return 'Any monthly rent';
};

const BudgetSlider: React.FC<{
  bounds: QuickRefineRentBounds;
  values: QuickRefineRentValues;
  onChange: (thumb: QuickRefineRentThumb, value: number) => void;
  onCommit: () => void;
}> = ({ bounds, values, onChange, onCommit }) => {
  const [activeThumb, setActiveThumb] = useState<QuickRefineRentThumb>('min');
  const canAdjust = bounds.max > bounds.min;
  const maxLabel = 'Any';
  const minPosition = quickRefineRentToSliderPosition(values.min, bounds, 'min');
  const maxPosition = quickRefineRentToSliderPosition(values.max, bounds, 'max');
  const left = canAdjust ? `${(minPosition / QUICK_REFINE_RENT_SLIDER_MAX_POSITION) * 100}%` : '0%';
  const width = canAdjust
    ? `${((maxPosition - minPosition) / QUICK_REFINE_RENT_SLIDER_MAX_POSITION) * 100}%` : '0%';

  return <fieldset className="min-w-0">
    <legend className="mb-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-600">Monthly rent</legend>
    <div className="mb-1 flex min-w-0 items-center justify-between gap-2 text-[10px] font-medium text-slate-500">
      <span>₹{formatRentDisplay(bounds.min)}</span>
      <span className="truncate rounded-full border border-[#ddcfaa] bg-[#fffaf0] px-2.5 py-1 text-[11px] font-semibold text-emerald-950 shadow-[0_3px_9px_-7px_rgba(20,60,45,.55)]" aria-live="polite">
        {rentRangeLabel(bounds, values)}
      </span>
      <span>{maxLabel}</span>
    </div>
    <div className={`relative mx-2 h-11 ${canAdjust ? '' : 'opacity-50'}`}>
      <div className="absolute inset-x-0 top-1/2 h-[3px] -translate-y-1/2 rounded-full bg-[#dddcd2]" aria-hidden="true" />
      <div className="absolute top-1/2 h-[3px] -translate-y-1/2 rounded-full bg-gradient-to-r from-[#6aa88b] to-[#087151] shadow-[0_0_7px_rgba(16,112,78,.18)]" style={{ left, width }} aria-hidden="true" />
      <input type="range" min={0} max={QUICK_REFINE_RENT_SLIDER_MAX_POSITION - 1}
        step={1} value={minPosition}
        disabled={!canAdjust} aria-label="Minimum monthly rent" aria-valuetext={values.min <= bounds.min
          ? 'No minimum rent' : `Minimum monthly rent ${formatRentDisplay(values.min)} rupees`}
        onFocus={() => setActiveThumb('min')} onChange={event => onChange('min',
          quickRefineSliderPositionToRent(Number(event.currentTarget.value), bounds, 'min'))}
        onPointerUp={onCommit} onPointerCancel={onCommit}
        className={`tenant-quick-refine-slider ${activeThumb === 'min' ? 'is-active' : ''}`} />
      <input type="range" min={0} max={QUICK_REFINE_RENT_SLIDER_MAX_POSITION}
        step={1} value={maxPosition}
        disabled={!canAdjust} aria-label="Maximum monthly rent" aria-valuetext={values.max >= bounds.max
          ? 'No maximum rent' : `Maximum monthly rent ${formatRentDisplay(values.max)} rupees`}
        onFocus={() => setActiveThumb('max')} onChange={event => onChange('max',
          quickRefineSliderPositionToRent(Number(event.currentTarget.value), bounds, 'max'))}
        onPointerUp={onCommit} onPointerCancel={onCommit}
        className={`tenant-quick-refine-slider ${activeThumb === 'max' ? 'is-active' : ''}`} />
    </div>
  </fieldset>;
};

const BotanicalSprig: React.FC<{ className: string }> = ({ className }) => <svg viewBox="0 0 180 150" aria-hidden="true" className={`pointer-events-none absolute select-none ${className}`}>
  <g fill="none" stroke="#557d62" strokeLinecap="round" strokeWidth="1.15">
    <path d="M9 143C43 118 69 92 91 65s41-43 76-55" />
    <path d="M36 123C24 100 20 83 25 65M58 103C79 99 94 88 106 70M79 81C66 58 63 39 70 21M104 57C125 58 142 50 155 37M127 35C117 19 115 8 120 0" />
  </g>
  <g stroke="#597e62" strokeWidth=".7">
        <path d="M25 66C7 55 3 40 8 25c14 3 23 15 17 41Z" fill="#a8b99a" fillOpacity=".55" />
    <path d="M26 65 11 30M24 102C6 99-2 87 1 72c15 0 25 10 23 30Z" fill="#829b7d" fillOpacity=".48" />
    <path d="M58 103c4-20 18-29 34-28-1 16-12 28-34 28Z" fill="#b4bfa0" fillOpacity=".55" />
    <path d="M70 22C53 14 48 1 54-13c15 5 22 17 16 35Z" fill="#849b7d" fillOpacity=".52" />
    <path d="M72 21c12-18 26-22 39-14-7 14-20 20-39 14Z" fill="#c3b184" fillOpacity=".45" />
    <path d="M105 71c2-19 13-29 28-30 1 15-8 27-28 30Z" fill="#9eaf90" fillOpacity=".55" />
    <path d="M106 70c14-17 28-20 40-11-8 13-21 17-40 11Z" fill="#7f9978" fillOpacity=".46" />
    <path d="M155 37c2-17 12-26 25-26 1 14-8 24-25 26Z" fill="#aebb9e" fillOpacity=".55" />
    <path d="M120 1c-12-14-11-26-2-36 12 8 15 20 2 36Z" fill="#81977a" fillOpacity=".45" />
  </g>
  <g fill="none" stroke="#6d896d" strokeLinecap="round" strokeWidth=".65" opacity=".75">
    <path d="m25 65-13-33m12 68L5 76m53 27 27-26M70 21 57-8m49 78 25-25m24-29 17-21" />
  </g>
  <g fill="#bd9b52" fillOpacity=".62">
    <circle cx="26" cy="64" r="1.8" /><circle cx="107" cy="70" r="1.65" />
    <circle cx="155" cy="36" r="1.5" /><circle cx="71" cy="21" r="1.35" />
  </g>
</svg>;

const BotanicalDetails: React.FC<{ mobile?: boolean }> = ({ mobile = false }) => <>
  {mobile ? <>
    <BotanicalSprig className="right-[-2rem] top-[-1.7rem] h-28 w-32 rotate-[16deg] opacity-35" />
    <BotanicalSprig className="bottom-[-2rem] left-[-2.3rem] h-32 w-36 rotate-[175deg] opacity-30" />
  </> : <>
    <BotanicalSprig className="left-[-1.2rem] top-[-1.5rem] h-28 w-32 rotate-[-18deg] opacity-35" />
    <BotanicalSprig className="right-[-2rem] top-[-1.7rem] h-40 w-44 rotate-[18deg] opacity-40" />
    <BotanicalSprig className="bottom-[-2.7rem] left-[-2rem] h-44 w-48 rotate-[174deg] opacity-35" />
  </>}
</>;

const ResultSummary: React.FC<QuickRefineBaseProps> = props => {
  const updating = props.discoveryState !== 'ERROR' && (props.discoveryState === 'LOADING'
    || props.discoveryLoadedKey !== discoverySearchKey(props.filters));
  return <span className="inline-flex min-w-0 items-center gap-1.5">
    {updating && <LoaderCircle size={12} className="shrink-0 motion-safe:animate-spin" aria-hidden="true" />}
    <span>{quickRefineResultSummary(props.filters, props.discoveryState, props.discoveryLoadedKey, props.homesLoaded)}</span>
  </span>;
};

const useQuickRefineActions = (filters: RentalSearchFilters, discoveryCity: string, onSearchHomes: (filters: RentalSearchFilters) => void) => {
  const toggle = (dimension: 'bhk' | 'propertyType' | 'furnishing', value: string) => {
    onSearchHomes(updateQuickRefineFilter(filters, dimension, value as never));
  };
  const clearDimension = (dimension: QuickRefineDimension) => {
    if (dimension === 'budget') {
      onSearchHomes({ ...filters, minRent: undefined, maxRent: undefined });
    } else if (dimension === 'bhk' || dimension === 'propertyType' || dimension === 'furnishing') {
      const value = filters[dimension];
      if (value) onSearchHomes(updateQuickRefineFilter(filters, dimension, value as never));
    }
  };
  const clearAll = () => {
    onSearchHomes(clearQuickRefineFilters(filters, discoveryCity));
  };
  return { toggle, clearDimension, clearAll };
};

export const TenantQuickRefinePanel: React.FC<QuickRefineBaseProps> = props => {
  const { filters, discoveryCity, onSearchHomes } = props;
  const id = useId();
  const budget = useRentSliderControls(filters, props.rentBounds, onSearchHomes);
  const { toggle, clearAll } = useQuickRefineActions(filters, discoveryCity, onSearchHomes);
  const activeCount = quickRefineActiveCount(filters);
  const clearFilters = () => {
    budget.cancelPending();
    clearAll();
  };

  return (
    <section aria-labelledby={`${id}-title`} className="relative isolate mt-3 hidden min-w-0 overflow-hidden rounded-[24px] border border-[#dfd5bd] bg-gradient-to-br from-[#fffdf7] via-[#fafbf5] to-[#edf4e9] p-3.5 shadow-[0_12px_30px_-27px_rgba(15,45,34,.55)] lg:block xl:p-4">
      <BotanicalDetails />
      <div className="relative z-10">
        <div className="flex min-w-0 items-center gap-2.5">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-[#dfd3b5] bg-gradient-to-br from-[#fff9e9] to-[#edf3e9] text-emerald-900 shadow-[0_3px_8px_-6px_rgba(20,60,45,.45)]"><SlidersHorizontal size={16} aria-hidden="true" /></span>
          <div className="min-w-0 flex-1">
            <h2 id={`${id}-title`} className="whitespace-nowrap font-serif text-[17px] font-medium leading-5 tracking-tight text-slate-950">Quick Refine</h2>
            <p className="mt-0.5 min-w-0 truncate text-[11px] leading-4 text-slate-600" aria-live="polite"><ResultSummary {...props} /></p>
          </div>
          {activeCount > 0 && <button type="button" onClick={clearFilters} className={`min-h-11 shrink-0 rounded-lg px-1.5 text-[11px] font-semibold text-emerald-900 underline decoration-emerald-700/35 underline-offset-4 hover:bg-white/70 ${focusClass}`}>Clear all</button>}
        </div>

        <div className="mt-3.5"><QuickRefineOptions filters={filters} onToggle={toggle} /></div>
        <div className="mt-3.5 border-t border-emerald-950/10 pt-2.5">
              <BudgetSlider bounds={props.rentBounds} values={budget.values}
            onChange={budget.change} onCommit={budget.commitPending} />
        </div>
        {activeCount > 0 && <p className="relative mt-1 text-right text-[10px] font-medium text-slate-500">{activeCount} {activeCount === 1 ? 'filter' : 'filters'} applied</p>}
      </div>
    </section>
  );
};

interface TenantQuickRefineMobileProps extends QuickRefineBaseProps {
  openRequestRef: React.MutableRefObject<(() => void) | null>;
  onSheetOpenChange: (isOpen: boolean) => void;
}

export const TenantQuickRefineMobile: React.FC<TenantQuickRefineMobileProps> = props => {
  const { filters, discoveryCity, onSearchHomes } = props;
  const id = useId();
  const budget = useRentSliderControls(filters, props.rentBounds, onSearchHomes);
  const { toggle, clearDimension, clearAll } = useQuickRefineActions(filters, discoveryCity, onSearchHomes);
  const activeCount = quickRefineActiveCount(filters);
  const chips = useMemo(() => quickRefineChipLabels(filters), [filters]);
  const [isOpen, setIsOpen] = useState(false);
  const sheetRef = useRef<HTMLDivElement>(null);

  const closeSheet = (restoreFocus: boolean) => {
    props.onSheetOpenChange(false);
    setIsOpen(false);
    if (restoreFocus) window.setTimeout(() => document.getElementById('tenant-mobile-quick-refine-trigger')?.focus(), 0);
  };

  useEffect(() => {
    const open = () => {
      props.onSheetOpenChange(true);
      setIsOpen(true);
    };
    props.openRequestRef.current = open;
    return () => {
      if (props.openRequestRef.current === open) props.openRequestRef.current = null;
    };
  }, [props.openRequestRef, props.onSheetOpenChange]);

  const clearFilters = () => {
    budget.cancelPending();
    clearAll();
  };

  useEffect(() => {
    if (!isOpen) return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    window.setTimeout(() => sheetRef.current?.querySelector<HTMLElement>('button, input')?.focus(), 0);

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeSheet(true);
        return;
      }
      if (event.key !== 'Tab' || !sheetRef.current) return;
      const focusable = [...sheetRef.current.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
      )].filter(element => element.offsetParent !== null);
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
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  return <>
    {chips.length > 0 && <div className="mb-4 min-w-0 lg:hidden" aria-label="Active search filters">
      <div className="flex min-w-0 items-center gap-2 overflow-x-auto overscroll-x-contain pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {chips.map(chip => <span key={chip.dimension} className="relative inline-flex min-h-10 shrink-0 items-center rounded-full border border-emerald-800/20 bg-[#edf5ec] pl-3 pr-11 text-xs font-medium text-emerald-950">
          {chip.label}
          <button type="button" onClick={() => clearDimension(chip.dimension)} aria-label={`Remove ${chip.label} filter`}
            style={{ width: QUICK_REFINE_CHIP_REMOVE_TARGET_PX, height: QUICK_REFINE_CHIP_REMOVE_TARGET_PX }}
            className={`absolute right-0 top-1/2 flex -translate-y-1/2 items-center justify-center rounded-full text-emerald-900 hover:bg-white/80 ${focusClass}`}><X size={14} aria-hidden="true" /></button>
        </span>)}
      </div>
    </div>}

    {isOpen && createPortal(
      <div className="fixed inset-0 z-[100] flex items-end justify-center lg:hidden" role="presentation">
        <button type="button" tabIndex={-1} aria-label="Close filters" onClick={() => closeSheet(true)} className="absolute inset-0 bg-[#101b17]/45 backdrop-blur-[2px]" />
        <section ref={sheetRef} id="tenant-mobile-quick-refine" role="dialog" aria-modal="true" aria-labelledby={`${id}-title`}
          className="relative flex max-h-[min(92dvh,56rem)] w-full flex-col overflow-hidden rounded-t-[28px] border border-white/80 bg-gradient-to-b from-[#fffdf8] via-[#fbfcf8] to-[#f2f7f1] shadow-[0_-20px_60px_-24px_rgba(5,35,25,.4)]">
          <BotanicalDetails mobile />
          <div className="relative z-10 shrink-0 border-b border-emerald-950/10 px-5 pb-4 pt-3" style={{ paddingTop: 'max(.75rem, env(safe-area-inset-top))' }}>
            <div className="mx-auto mb-3 h-1 w-10 rounded-full bg-emerald-900/20" aria-hidden="true" />
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-3">
                <span className="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl bg-[#edf4e9] text-emerald-900"><SlidersHorizontal size={18} aria-hidden="true" /></span>
                <div className="relative z-10"><h2 id={`${id}-title`} className="font-serif text-2xl font-medium tracking-tight text-slate-950">Quick Refine</h2>
                  <p className="mt-1 text-xs text-slate-600" aria-live="polite"><ResultSummary {...props} /></p>
                </div>
              </div>
              <div className="flex items-center gap-1">
                {activeCount > 0 && <button type="button" onClick={clearFilters} className={`relative z-10 min-h-11 rounded-lg px-2 text-xs font-semibold text-emerald-900 underline underline-offset-4 ${focusClass}`}>Clear all</button>}
                <button type="button" onClick={() => closeSheet(true)} aria-label="Close Quick Refine" className={`flex h-11 w-11 items-center justify-center rounded-full border border-[#e3e4dc] bg-white/80 text-slate-700 hover:bg-white ${focusClass}`}><X size={18} aria-hidden="true" /></button>
              </div>
            </div>
          </div>

          <div className="relative z-10 min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-5">
            <QuickRefineOptions filters={filters} onToggle={toggle} />
            <div className="mt-5 border-t border-emerald-950/10 pt-4">
          <BudgetSlider bounds={props.rentBounds} values={budget.values}
                onChange={budget.change} onCommit={budget.commitPending} />
            </div>
            {activeCount > 0 && <p className="mt-5 text-xs font-medium text-slate-600">{activeCount} {activeCount === 1 ? 'filter' : 'filters'} applied</p>}
          </div>
          <div className="safe-area-bottom shrink-0 bg-[#fffefa]/75 px-5 pt-2" aria-hidden="true" />
        </section>
      </div>, document.body
    )}
  </>;
};
